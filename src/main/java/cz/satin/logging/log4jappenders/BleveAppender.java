package cz.satin.logging.log4jappenders;

import okhttp3.*;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.io.IOException;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static cz.satin.logging.log4jappenders.Config.*;

/**
 * Created by Lukas Satin
 */
@Plugin(name = "BleveAppender", category = "Core", elementType = "appender", printObject = true)
public class BleveAppender extends AbstractAppender {

    private static final String AWS_INSTANCE_ID; // per-instance, so static

    private static volatile BleveService bleveService;

    private static final ConcurrentLinkedQueue<retrofit2.Call<Object>> queue = new ConcurrentLinkedQueue<>();

    static {
        AWS_INSTANCE_ID = retrieveInstanceId();
    }

    private final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd.MM.yyyy HH.mm.ss"); // aws doesn't allow ":" in stream name

    private String hmacToken;
    private String indexName;
    private boolean extraDebug;

    private volatile boolean shutdown = false;
    private final int flushPeriodMillis = 10000;
    private static Thread deliveryThread;
    private final Object monitor = new Object();

    private final String MEDIA_TYPE = "application/json; charset=utf-8";

    private BleveAppender(final String name,
                          final String url,
                          final String indexName,
                          final String hmacToken,
                          final boolean extraDebug,
                          final Layout<Serializable> layout) {
        super(name, null, layout == null ? PatternLayout.createDefaultLayout() : layout, false, Property.EMPTY_ARRAY);

        this.hmacToken = hmacToken;
        this.indexName = indexName;
        this.extraDebug = extraDebug;

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new HeaderInterceptor())
                //.addInterceptor(new PathParamInterceptor("indexName", indexName))
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(url)
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create())
                .build();

        bleveService = retrofit.create(BleveService.class);

        try {
            bleveService.addIndex(indexName).execute();
        } catch (IOException e) {
            // just try to re-register index if user forgot to read our install steps
        }
    }

    @PluginFactory
    public static BleveAppender createAppender(@PluginAttribute("name") String name,
                                               @PluginAttribute("url") String url,
                                               @PluginAttribute("indexName") String indexName,
                                               @PluginAttribute("hmacToken") String hmacToken,
                                               @PluginAttribute("extraDebug") boolean extraDebug,
                                               @PluginElement("Layout") Layout<Serializable> layout) {
        return new BleveAppender(
                name == null ? DEFAULT_LOG_APPENDER_NAME : name,
                url == null ? DEFAULT_URL : url,
                indexName == null ? "log4j2-default" : indexName,
                hmacToken == null ? "" : hmacToken,
                extraDebug,
                layout);
    }

    /**
     * Create Bleeve log event based on the log4j log event and send it
     */
    @Override
    public void append(final LogEvent event) {

        final long timestamp = event.getTimeMillis();

        final String message = new String(getLayout().toByteArray(event));

        RequestBody body = RequestBody.create(okhttp3.MediaType.parse(MEDIA_TYPE), message);

        retrofit2.Call<Object> call = bleveService.addIndex(indexName,"(" + simpleDateFormat.format(timestamp) + ") " + AWS_INSTANCE_ID + " " + event.getLoggerName().replaceAll("[\\[\\]]", ""), body);
        enqueueCall(call);

    }

    private void enqueueCall(retrofit2.Call<Object> call) {
        if (extraDebug) {
            debug(call.request().toString());
        }

        call.enqueue(new retrofit2.Callback<Object>() {
            @Override
            public void onResponse(retrofit2.Call<Object> call, retrofit2.Response<Object> response) {
                if (response.isSuccessful()) {
                    // result available
                } else {
                    // error response, no access to resource?

                    if (extraDebug) {
                        debug(call.request().url().toString());
                        debug(response.headers().toString());
                        try {
                            if (null != response.errorBody()) {
                                debug(response.errorBody().string());
                            }
                        } catch (IOException e) {
                            debug(e.getMessage());
                        }
                    }

                    // When it fails here, we don't put in queue as this is only for debug purposes
                    // Error here means we are trying to add some wrong metadata in wrong format and
                    // this should be solved during the development
                    //queue.add(call.clone());
                }
            }

            @Override
            public void onFailure(retrofit2.Call<Object> call, Throwable throwable) {
                // something went completely south (like no internet connection)
                if (extraDebug) {
                    debug(throwable.getMessage());
                }
                queue.add(call.clone());
            }

        });
    }

    private Runnable messageProcessor = new Runnable() {
        @Override
        public void run() {
            debug("Draining queue for " + indexName + " stream every " + (flushPeriodMillis / 1000) + "s...");
            while (!shutdown) {
                try {
                    flush();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                if (!shutdown && queue.size() < AWS_DRAIN_LIMIT) {
                    try {
                        synchronized (monitor) {
                            monitor.wait(flushPeriodMillis);
                        }
                    } catch (InterruptedException ix) {
                        ix.printStackTrace();
                    }
                }
            }


            flush();

        }
    };

    private void flush() {
        if (!queue.isEmpty()) {
            debug("Draining " + queue.size() + " log items");
        }
        while (!queue.isEmpty()) {
            enqueueCall(queue.poll());
        }
    }

    @Override
    public void start() {
        super.start();
        if (null == deliveryThread) {
            debug("Starting BleveAppender for: " + indexName);
            deliveryThread = new Thread(messageProcessor, "BleveAppenderDeliveryThread");
            deliveryThread.start();
        }
    }

    @Override
    public void stop() {
        super.stop();
        shutdown = true;
        if (deliveryThread != null) {
            synchronized (monitor) {
                monitor.notify();
            }
            try {
                deliveryThread.join(SHUTDOWN_TIMEOUT_MILLIS);
            } catch (InterruptedException ix) {
                ix.printStackTrace();
            }
        }
        if (queue.size() > 0) {
            flush();
        }
    }

    private String getTimeNow() {
        return simpleDateFormat.format(new Date());
    }

    private void debug(final String s) {
        System.out.println(getTimeNow() + " BleveAppender: " + s);
    }

    public class PathParamInterceptor implements Interceptor {
        private final String mKey;
        private final String mValue;

        private PathParamInterceptor(String key, String value) {
            mKey = String.format("{%s}", key);
            mValue = value;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request originalRequest = chain.request();

            HttpUrl.Builder urlBuilder = originalRequest.url().newBuilder();
            List<String> segments = originalRequest.url().pathSegments();

            for (int i = 0; i < segments.size(); i++) {
                if (mKey.equalsIgnoreCase(segments.get(i))) {
                    urlBuilder.setPathSegment(i, mValue);
                }
            }

            Request request = originalRequest.newBuilder()
                    .url(urlBuilder.build())
                    .build();
            return chain.proceed(request);
        }
    }

    public class HeaderInterceptor
            implements Interceptor {
        @Override
        public okhttp3.Response intercept(Interceptor.Chain chain)
                throws IOException {
            Request request = chain.request();
            request = request.newBuilder()
                    .addHeader("Authorization", "Bearer " + hmacToken)
                    .build();
            okhttp3.Response response = chain.proceed(request);
            return response;
        }
    }
}
