package tstxxy.gra;

import com.sun.tools.jconsole.JConsoleContext;
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

    private JWTAuth provider;

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        provider = JWTAuth.create(vertx, new JWTAuthOptions()
            .addPubSecKey(new PubSecKeyOptions()
                .setAlgorithm("HS256")
                .setBuffer("keyboard cat")));

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
        System.out.println(username);
        System.out.println(password);
        if ("tstxxy".equals(username) && "qwer1234".equals(password)) {
            ctx.json(new JsonObject().put("state", "Yes"));
            String token = provider.generateToken(new JsonObject().put("username", username),
                new JWTOptions().setExpiresInSeconds(120));
            ctx.response().putHeader("Authorization", token);
        } else {
            ctx.json(new JsonObject().put("state", "No"));
        }


        response.end();
    }
}
