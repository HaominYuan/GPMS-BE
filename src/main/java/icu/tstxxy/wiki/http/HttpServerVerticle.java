package icu.tstxxy.wiki.http;

import com.github.rjeschke.txtmark.Processor;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.handler.*;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.templ.freemarker.FreeMarkerTemplateEngine;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;

import java.util.Arrays;
import java.util.Date;

public class HttpServerVerticle extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);
    private static final String EMPTY_PAGE_MARKDOWN = "# A new page\n\nFeel-free to write in Markdown!\n";
    private final String wikiDbQueue = "wikidb.queue";
    private FreeMarkerTemplateEngine templateEngine;
    private WebClient webClient;
    private JWTAuth jwtAuth;

    @Override
    public void start(Promise<Void> startPromise) {
        templateEngine = FreeMarkerTemplateEngine.create(vertx);
        webClient = WebClient.create(vertx, new WebClientOptions().setUserAgent("tstxxy").setSsl(true));

        PgPool dbClient = PgPool.pool(vertx, new PgConnectOptions()
            .setPort(5432)
            .setHost("localhost")
            .setDatabase("wiki")
            .setUser("postgres")
            .setPassword("qwer1234"), new PoolOptions().setMaxSize(5));

//        sqlAuth = SqlAuthentication.create(dbClient, new SqlAuthenticationOptions());
        jwtAuth = JWTAuth.create(vertx, new JWTAuthOptions().addPubSecKey(new PubSecKeyOptions()
            .setAlgorithm("HS256").setBuffer("secret")));

        HttpServer server = vertx.createHttpServer(new HttpServerOptions()
            .setSsl(true)
            .setKeyStoreOptions(new JksOptions()
                .setPath("server-keystore.jks")
                .setPassword("secret")));

        server.requestHandler(getRouter()).listen(443).onFailure(e -> {
            LOGGER.error(e.getMessage());
            startPromise.fail(e.getCause());
        });
    }

    private void apiDeletePage(RoutingContext context) {
        int id = Integer.parseInt(context.request().getParam("id"));
        var request = new JsonObject().put("id", id);
        var options = new DeliveryOptions().addHeader("action", "delete-page");

        handleSimpleRequest(context, request, options, 200);
    }

    private void apiUpdatePage(RoutingContext context) {
        int id = Integer.parseInt(context.request().getParam("id"));
        var page = context.getBodyAsJson();
        if (!validateJsonPageDocument(context, page, "markdown")) return;
        var request = new JsonObject().put("id", id).put("markdown", page.getString("markdown"));
        var options = new DeliveryOptions().addHeader("action", "save-page");
        handleSimpleRequest(context, request, options, 200);
    }

    private void apiCreatePage(RoutingContext context) {
        JsonObject page = context.getBodyAsJson();
        if (!validateJsonPageDocument(context, page, "title", "markdown")) return;
        var options = new DeliveryOptions().addHeader("action", "create-page");
        var request = new JsonObject().put("title", page.getString("title"))
            .put("markdown", page.getString("markdown"));

        handleSimpleRequest(context, request, options, 201);
    }

    private void apiRoot(RoutingContext context) {
        DeliveryOptions options = new DeliveryOptions().addHeader("action", "all-pages-data");
        vertx.eventBus().request(wikiDbQueue, new JsonObject(), options).compose(
            message -> context.response().setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("success", true).put("pages", message.body()).encode()))
            .onFailure(e -> context.response().setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("success", true).put("error", e.getCause()).encode()));
    }

    private void apiGetPage(RoutingContext context) {
        var id = Integer.parseInt(context.request().getParam("id"));
        var options = new DeliveryOptions().addHeader("action", "get-page-by-id");
        vertx.eventBus().request(wikiDbQueue, new JsonObject().put("id", id), options).compose(message -> {
            JsonObject body = (JsonObject) message.body();
            var response = new JsonObject();
            if (body.getBoolean("found")) {
                var payload = new JsonObject()
                    .put("title", body.getString("title"))
                    .put("id", body.getInteger("id"))
                    .put("markdown", body.getString("content"))
                    .put("html", Processor.process(body.getString("content")));
                response.put("success", true).put("page", payload);
                context.response().setStatusCode(200);
            } else {
                response.put("success", false).put("error", "There is no page with ID " + id);
                context.response().setStatusCode(404);
            }
            return context.response().putHeader("Content-Type", "application/json").end(response.encode());
        }).onFailure(e -> context.response().setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("success", false).put("error", e.getMessage()).encode()));
    }

    private void indexHandler(RoutingContext context) {
        DeliveryOptions options = new DeliveryOptions().addHeader("action", "all-pages");
        vertx.eventBus().request(wikiDbQueue, new JsonObject(), options).compose(message -> {
            JsonObject body = (JsonObject) message.body();
            context.put("title", "Wiki home")
                .put("pages", body.getJsonArray("pages").getList())
                .put("canCreatePage", true)
                .put("username", "asfd");

            return templateEngine.render(context.data(), "templates/index.ftl")
                .compose(buffer -> context.response().putHeader("Content-Type", "text/html").end(buffer));
        }).onFailure(e -> {
            LOGGER.error(e.getMessage());
            context.fail(e.getCause());
        });
    }

    private void backupHandler(RoutingContext context) {
        DeliveryOptions options = new DeliveryOptions().addHeader("action", "all-pages-data");
        vertx.eventBus().request(wikiDbQueue, new JsonObject(), options).compose(message -> {

            JsonArray array = (JsonArray) message.body();
            JsonArray newA = new JsonArray();
            array.forEach(object -> {
                var temp = ((JsonObject) object);
                newA.add(new JsonObject().put("name", temp.getString("title"))
                    .put("content", temp.getString("content")));
            });

            JsonObject payload = new JsonObject()
                .put("files", newA)
                .put("language", "plaintext")
                .put("title", "tstxxy-wiki-backup")
                .put("public", true);

            return webClient.post(443, "snippets.glot.io", "/snippets")
                .putHeader("Content-Type", "application/json")
                .as(BodyCodec.jsonObject())
                .sendJsonObject(payload).onSuccess(response -> {
                    if (response.statusCode() == 200) {
                        String url = "https://glot.io/snippets/" + response.body().getString("id");
                        context.put("backup_gist_url", url);
                        indexHandler(context);
                    } else {
                        StringBuilder m = new StringBuilder().append("Could not backup the wiki:")
                            .append(response.statusMessage());
                        JsonObject body = response.body();
                        if (body != null) {
                            m.append(System.getProperty("line.separator")).append(body.encodePrettily());
                        }
                        LOGGER.error(m.toString());
                        context.fail(502);
                    }
                });
        }).onFailure(e -> {
            LOGGER.error(e.getCause());
            context.fail(e.getCause());
        });
    }

    private void pageRenderingHandler(RoutingContext context) {
        // 请求链接拿数据
        String requestedPage = context.request().getParam("page");
        // 设置数据头
        DeliveryOptions options = new DeliveryOptions().addHeader("action", "get-page");
        // 通过 json 格式发送数据
        JsonObject request = new JsonObject().put("page", requestedPage);
        // 通过 eventbus 发送请求
        vertx.eventBus().request(wikiDbQueue, request, options).compose(message -> {
            JsonObject body = (JsonObject) message.body();
            boolean found = body.getBoolean("found");
            String rawContent = body.getString("rawContent", EMPTY_PAGE_MARKDOWN);

            context.put("title", requestedPage)
                .put("id", body.getInteger("id", -1))
                .put("newPage", found ? "no" : "yes")
                .put("rawContent", rawContent)
                .put("content", Processor.process(rawContent))
                .put("timestamp", new Date().toString());

            return templateEngine.render(context.data(), "templates/page.ftl")
                .compose(buffer -> context.response().putHeader("Content-Type", "text/html").end(buffer));
        }).onFailure(e -> {
            context.fail(e.getCause());
            LOGGER.error(e.getMessage());
        });
    }

    private void pageUpdateHandler(RoutingContext context) {
        String title = context.request().getParam("title");
        JsonObject request = new JsonObject()
            .put("id", context.request().getParam("id"))
            .put("title", title)
            .put("markdown", context.request().getParam("markdown"));


        DeliveryOptions options = new DeliveryOptions();
        if ("yes".equals(context.request().getParam("newPage"))) {
            options.addHeader("action", "create-page");
        } else {
            options.addHeader("action", "save-page");
        }

        vertx.eventBus().request(wikiDbQueue, request, options).compose(message -> context.response()
            .setStatusCode(303)
            .putHeader("Location", "/wiki/" + title)
            .end())
            .onFailure(e -> {
            context.fail(e.getCause());
            LOGGER.error(e.getMessage());
        });
    }

    private void pageCreateHandler(RoutingContext context) {
        String pageTitle = context.request().getParam("title");
        String location = "/wiki/" + pageTitle;
        if (pageTitle == null || pageTitle.isEmpty()) {
            location = "/";
        }
        context.response().setStatusCode(303).putHeader("Location", location).end()
            .onFailure(e -> {
                context.fail(e);
                LOGGER.error(e);
        });
    }

    private void pageDeletionHandler(RoutingContext context) {
        String id = context.request().getParam("id");
        JsonObject request = new JsonObject().put("id", id);
        DeliveryOptions options = new DeliveryOptions().addHeader("action", "delete-page");
        vertx.eventBus().request(wikiDbQueue, request, options).compose(message -> context.response()
            .setStatusCode(303)
            .putHeader("Location", "/")
            .end()).onFailure(e -> {
                context.fail(e);
                LOGGER.error(e);
        });
    }

    private boolean validateJsonPageDocument(RoutingContext context, JsonObject page, String... expectedKeys) {
        if (!Arrays.stream(expectedKeys).allMatch(page::containsKey)) {
            LOGGER.error("Bad page creation JSON payload: "
                + page.encodePrettily() + " from " + context.request().remoteAddress());
            context.response().setStatusCode(400)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("success", false).put("error", "Bad request payload").encode());
            return false;
        }
        return true;
    }

    private void handleSimpleRequest(RoutingContext context, JsonObject request, DeliveryOptions options, int code) {
        vertx.eventBus().request(wikiDbQueue, request, options).compose(message -> context.response()
            .setStatusCode(code).putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("success", true).encode()))
            .onFailure(e -> {
                LOGGER.error(e.getCause());
                context.response().setStatusCode(500).putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("success", false).put("error", e.getMessage()).encode());
            });
    }

    private void logoutHandler(RoutingContext context) {
        context.clearUser();
        context.response().setStatusCode(302).putHeader("Location", "/").end();
    }

    private void loginHandler(RoutingContext context) {
        context.put("title", "Login");
        templateEngine.render(context.data(), "templates/login.ftl")
            .compose(buffer -> context.response().putHeader("Content-Type", "text/html").end(buffer))
            .onFailure(e -> {
                context.fail(e);
                LOGGER.error(e.getMessage());
            });
    }

    private void loginAuthHandler(RoutingContext context) {
        var request = new JsonObject().put("username", context.request().getParam("username"))
            .put("password", context.request().getParam("password"));
        var options = new DeliveryOptions().addHeader("action", "authenticate");

        vertx.eventBus().request(wikiDbQueue, request, options)
            .compose(message -> context.response().setStatusCode(302)
                .putHeader("Location", context.request().getParam("return_url")).end())
            .onFailure(e -> {
                context.fail(e.getCause());
                LOGGER.error(e.getMessage());
            });
    }

    private void apiToken(RoutingContext context) {
        var request = new JsonObject().put("username", context.request().getParam("username"))
            .put("password", context.request().getParam("password"));
        var options = new DeliveryOptions().addHeader("action", "authenticate");

        vertx.eventBus().request(wikiDbQueue, request, options).compose(message -> {
            String token = jwtAuth.generateToken(new JsonObject().put("username", request.getString("username")),
                new JWTOptions().setSubject("Wiki Api").setIssuer("tstxxy")
            );

            return context.response().putHeader("Content-Type", "text/plain").end(token);
        }).onFailure(e -> {
            context.fail(e.getCause());
            LOGGER.error(e.getMessage());
        });
    }

    private Router getRouter() {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());


        // static
        router.get("/app/*").handler(StaticHandler.create().setCachingEnabled(false));
        router.get("/").handler(context -> context.reroute("/app/index.html"));




        router.post("/login-auth").handler(this::loginAuthHandler);
        router.get("/logout").handler(this::logoutHandler);
//        router.get("/").handler(this::indexHandler);
        router.get("/login").handler(this::loginHandler);
        router.get("/backup").handler(this::backupHandler);
        router.get("/wiki/:page").handler(this::pageRenderingHandler);
        router.post("/save").handler(this::pageUpdateHandler);
        router.post("/create").handler(this::pageCreateHandler);
        router.post("/delete").handler(this::pageDeletionHandler);

        Router apiRouter = Router.router(vertx);

        jwtAuth = JWTAuth.create(vertx, new JWTAuthOptions()
            .addPubSecKey(new PubSecKeyOptions().setAlgorithm("HS256").setBuffer("secret")));

        apiRouter.route("/pages").handler(JWTAuthHandler.create(jwtAuth));
        apiRouter.get("/token").handler(this::apiToken);
        apiRouter.get("/pages").handler(this::apiRoot);
        apiRouter.get("/pages/:id").handler(this::apiGetPage);
        apiRouter.post("/pages").handler(this::apiCreatePage);
        apiRouter.put("/pages/:id").handler(this::apiUpdatePage);
        apiRouter.delete("/pages/:id").handler(this::apiDeletePage);

        router.mountSubRouter("/api", apiRouter);
        return router;
    }
}


