package tstxxy.gra;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

public class MainVerticle extends AbstractVerticle {
    Router router;
    String login = "tstxxy";
    String password = "qwer1234";
    String identity = "teacher";

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        router = Router.router(vertx);

        router.get("/login").handler(ctx -> {
            var response = ctx.response();
            response.setChunked(true);

            var params = ctx.request().params();
            if (params.get(("username")).equals(login) && params.get("password").equals(password)) {
                ctx.json(new JsonObject().put("state", "Yes"));
            } else {
                ctx.json(new JsonObject().put("state", "No"));
            }
            response.end();

        });

        vertx.createHttpServer().requestHandler(router).listen(80, http -> {
            if (http.succeeded()) {
                startPromise.complete();
                System.out.println("HTTP server started on port 80");
            } else {
                startPromise.fail(http.cause());
            }
        });
    }
}
