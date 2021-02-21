package tstxxy.gra;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;


public class MainVerticle extends AbstractVerticle {

    private JWTAuth provider = JWTAuth.create(vertx, new JWTAuthOptions()
        .addPubSecKey(new PubSecKeyOptions()
            .setAlgorithm("HS256")
            .setBuffer("keyboard cat")));

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        Router router = Router.router(vertx);
        router.get("/login").handler(this::loginHandler);

        vertx.createHttpServer().requestHandler(router).listen(80, http -> {
            if (http.succeeded()) {
                startPromise.complete();
                System.out.println("HTTP server started on port 80");
            } else {
                startPromise.fail(http.cause());
            }
        });

    }

    private void loginHandler(RoutingContext ctx) {
        var response = ctx.response();
        response.setChunked(true);

        var params = ctx.request().params();
        String username = params.get("username");
        String password = params.get("password");

        if ("tstxxy".equals(username) && "qwer1234".equals(password)) {
            String token = provider.generateToken(new JsonObject().put("username", username),
                new JWTOptions().setExpiresInSeconds(12000));
            ctx.response().putHeader("accessToken", token);
            ctx.json(new JsonObject().put("state", "Yes"));
        } else {
            ctx.json(new JsonObject().put("state", "No"));
        }


        response.end();
    }
}
