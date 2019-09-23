package cz.satin.logging.log4jappenders;

import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.PUT;
import retrofit2.http.Path;

public interface BleveService {

    @PUT("/api/{indexName}/{docID}")
    Call<Object> addIndex(@Path("indexName") String indexName, @Path("docID") String docID, @Body RequestBody body);

    @PUT("/api/{indexName}")
    Call<Object> addIndex(@Path("indexName") String indexName);

}
