package tstxxy.gra;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;


import java.util.ArrayList;
import java.util.List;


public class MainVerticle extends AbstractVerticle {

    private JWTAuth provider = JWTAuth.create(vertx, new JWTAuthOptions()
        .addPubSecKey(new PubSecKeyOptions()
            .setAlgorithm("HS256")
            .setBuffer("keyboard cat")));

    private PgConnectOptions connectOptions = new PgConnectOptions()
        .setPort(5432)
        .setHost("localhost")
        .setDatabase("flower")
        .setUser("postgres")
        .setPassword("qwer1234");

    PoolOptions poolOptions = new PoolOptions()
        .setMaxSize(5);


    @Override
    public void start(Promise<Void> startPromise) {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.get("/login").handler(this::loginHandler);
        router.get("/flowers").handler(this::getFlowers);
        router.get("/flower").handler(this::getFlower);
        router.post("/flowerType").handler(this::postFlowerType);
        router.get("/flowerTypes").handler(this::getFlowerTypes);

        vertx.createHttpServer().requestHandler(router).listen(80, http -> {
            if (http.succeeded()) {
                startPromise.complete();
                System.out.println("HTTP server started on port 80");
            } else {
                startPromise.fail(http.cause());
            }
        });
    }

    private void getFlowers(RoutingContext ctx) {
        ctx.response().end("getFlowers");
    }

    private void getFlower(RoutingContext ctx) {
        ctx.response().end("getFlower");
    }

    private void postFlowerType(RoutingContext ctx) {
        PgPool client = PgPool.pool(vertx, connectOptions, poolOptions);




        JsonObject body = ctx.getBodyAsJson();

        System.out.println("body: " + body);

        ctx.response().end("postFlowerType");



    }

    private void getFlowerTypes(RoutingContext ctx) {
        try {
            PgPool client = PgPool.pool(vertx, connectOptions, poolOptions);
            client
                .query("SELECT * FROM flower_types ")
                .execute(ar -> {
                    if (ar.succeeded()) {
                        RowSet<Row> result = ar.result();
                        RowIterator iterator = result.iterator();
                        ObjectMapper mapper = new ObjectMapper();
                        while (iterator.hasNext()) {
                            try {
                                System.out.println("here");
                                System.out.println(mapper.writeValueAsString(iterator.next()));
                            } catch (JsonProcessingException e) {
                                e.printStackTrace();
                            } catch (Exception e) {
                                System.out.println(e);
                            }
                        }
                    } else {
                        System.out.println("Failure: " + ar.cause().getMessage());
                    }
                });
        } catch (Exception e) {
            System.out.println(e);
        }


        List result = new ArrayList();
        result.add(new JsonObject().put("id", "2").put("type", "asfd").put("description", "asdfsafdsad"));
        System.out.println(result);
        ctx.response().end(result.toString());
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
