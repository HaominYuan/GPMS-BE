package icu.tstxxy.wiki.database;


import io.vertx.codegen.annotations.Fluent;
import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@ProxyGen
@VertxGen
public interface DatabaseService {

    @Fluent
    DatabaseService fetchAllPages(Handler<AsyncResult<JsonArray>> resultHandler);

    @Fluent
    DatabaseVerticle fetchPage(String name, Handler<AsyncResult<JsonObject>> resultHandler);

    @Fluent
    DatabaseVerticle createPage(String title, String markdown, Handler<AsyncResult<Void>> resultHandler);

    @Fluent
    DatabaseVerticle savePage(int id, String markdown, Handler<AsyncResult<Void>> resultHandler);

    @Fluent
    DatabaseVerticle deletePage(int id, Handler<AsyncResult<Void>> resultHandler);


    @GenIgnore
    static DatabaseService createProxy(Vertx vertx, String address) {
    }
}
