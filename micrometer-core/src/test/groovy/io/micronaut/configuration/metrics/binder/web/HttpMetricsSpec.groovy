package io.micronaut.configuration.metrics.binder.web

import groovy.transform.InheritConstructors
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.distribution.HistogramSnapshot
import io.micrometer.core.instrument.search.MeterNotFoundException
import io.micronaut.context.ApplicationContext
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Error
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Produces
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import spock.lang.Specification
import spock.lang.Unroll

import static io.micronaut.configuration.metrics.micrometer.MeterRegistryFactory.MICRONAUT_METRICS_BINDERS
import static io.micronaut.configuration.metrics.micrometer.MeterRegistryFactory.MICRONAUT_METRICS_ENABLED
import static io.micronaut.http.HttpStatus.CONFLICT
import static io.micronaut.http.HttpStatus.NOT_FOUND

class HttpMetricsSpec extends Specification {

    @Unroll
    void "test client / server metrics"() {
        when:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [(cfg): setting])
        def context = embeddedServer.applicationContext
        TestClient client = context.getBean(TestClient)

        then:
        client.index() == 'ok'

        when:
        MeterRegistry registry = context.getBean(MeterRegistry)

        Timer serverTimer = registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri', '/test-http-metrics').timer()
        Timer clientTimer = registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags('uri', '/test-http-metrics').timer()
        HistogramSnapshot serverSnapshot = serverTimer.takeSnapshot()
        HistogramSnapshot clientSnapshot = clientTimer.takeSnapshot()

        then:
        serverTimer
        serverTimer.count() == 1
        clientTimer.count() == 1
        serverSnapshot.percentileValues().length == serverPercentilesCount
        clientSnapshot.percentileValues().length == clientPercentilesCount

        when: "A request is sent to the root route"

        then:
        client.root() == 'root'
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags('uri', 'root').timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri', 'root').timer()

        when: "A request is sent with a uri template"
        String result = client.template("foo")

        then:
        result == 'ok foo'
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags('uri', '/test-http-metrics/{id}').timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri', '/test-http-metrics/{id}').timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags('host', 'localhost').timer()

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri', '/test-http-metrics/foo').timer()

        then:
        thrown(MeterNotFoundException)

        when: "A request is made that returns an error response"
        client.error()

        then:
        thrown(HttpClientResponseException)

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags("status", "409").timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags("status", "409").timer()

        then:
        noExceptionThrown()

        when: "A request is made that throws an exception"
        client.throwable()

        then:
        thrown(HttpClientResponseException)

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags("status", "500").timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags("status", "500").timer()

        then:
        noExceptionThrown()

        when: "A request is made that throws an exception that is handled"
        client.exceptionHandling()

        then:
        thrown(HttpClientResponseException)

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags("status", "400").timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags("status", "400").timer()

        then:
        noExceptionThrown()

        when: "A request is made that does not match a route"
        HttpResponse response = client.notFound()

        then:
        noExceptionThrown()
        response.status() == NOT_FOUND

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags("status", "404").timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags("status", "404").timer()

        then:
        noExceptionThrown()

        when: "A request is made that does not match a route"
        client.noRouteMatchForMediaType()

        then:
        thrown(HttpClientResponseException)
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags('uri', '/test-http-metrics-no-route-match').timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags("status", "406").timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri', 'NO_ROUTE_MATCH').timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags("status", "406").timer()

        cleanup:
        embeddedServer.close()

        where:
        cfg                                                   | setting     | serverPercentilesCount | clientPercentilesCount
        MICRONAUT_METRICS_BINDERS + ".web.client.percentiles" | "0.95,0.99" | 0                      | 2
        MICRONAUT_METRICS_BINDERS + ".web.server.percentiles" | "0.95,0.99" | 2                      | 0
    }

    @Unroll
    void "test getting the beans #cfg #setting"() {
        when:
        ApplicationContext context = ApplicationContext.run([(cfg): setting])

        then:
        context.findBean(ClientRequestMetricRegistryFilter).isPresent() == setting
        context.findBean(ServerRequestMeterRegistryFilter).isPresent() == setting

        cleanup:
        context.close()

        where:
        cfg                           | setting
        MICRONAUT_METRICS_ENABLED     | true
        MICRONAUT_METRICS_ENABLED     | false
        (WebMetricsPublisher.ENABLED) | true
        (WebMetricsPublisher.ENABLED) | false
    }

    @Client('/')
    static interface TestClient {
        @Get
        String root()

        @Get('/test-http-metrics')
        String index()

        @Get("/test-http-metrics/{id}")
        String template(String id)

        @Get("/test-http-metrics/error")
        HttpResponse error()

        @Get("/test-http-metrics/throwable")
        HttpResponse throwable()

        @Get("/test-http-metrics/exception-handling")
        HttpResponse exceptionHandling()

        @Get("/test-http-metrics-not-found")
        HttpResponse notFound()

        @Get("/test-http-metrics-no-route-match")
        @Header(name = HttpHeaders.ACCEPT, value = MediaType.TEXT_PLAIN)
        HttpResponse noRouteMatchForMediaType()
    }

    @Controller('/')
    static class TestController {
        @Get
        String root() { "root" }

        @Get('/test-http-metrics')
        String index() { "ok" }

        @Get("/test-http-metrics/{id}")
        String template(String id) { "ok " + id }

        @Get("/test-http-metrics/error")
        HttpResponse error() {
            HttpResponse.status(CONFLICT)
        }

        @Get("/test-http-metrics/throwable")
        HttpResponse throwable() {
            throw new RuntimeException("error")
        }

        @Get("/test-http-metrics/exception-handling")
        HttpResponse exceptionHandling() {
            throw new MyException("my custom exception")
        }

        @Error(exception = MyException)
        HttpResponse<?> myExceptionHandler() {
            return HttpResponse.badRequest()
        }
        @Get("/test-http-metrics-no-route-match")
        @Produces(MediaType.APPLICATION_JSON)
        HttpResponse noRouteMatchForMediaType() {
            return HttpResponse.ok()
        }
    }

    @InheritConstructors
    static class MyException extends RuntimeException {
    }
}
