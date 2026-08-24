# ImmutableLog Micronaut Filter

Filtro Micronaut que captura automaticamente todas as requisições HTTP e envia eventos de auditoria imutáveis para o ImmutableLog.

Usa `HttpServerFilter` com `Publishers.map()` para interceptação reativa. Zero overhead de reflection — compatível com compilação nativa GraalVM.

---

## Instalação

```xml
<!-- pom.xml -->
<dependency>
  <groupId>io.micronaut</groupId>
  <artifactId>micronaut-http-server-netty</artifactId>
</dependency>
<dependency>
  <groupId>io.micronaut.serde</groupId>
  <artifactId>micronaut-serde-jackson</artifactId>
</dependency>
```

---

## Adicionar ao projeto

Copie `ImmutableLogFilter.java` para dentro do seu projeto:

```
src/main/java/com/example/filter/ImmutableLogFilter.java
```

A anotação `@Filter("/**")` registra o filtro globalmente — sem configuração extra.

---

## Variáveis de ambiente

| Variável             | Obrigatório | Default        | Descrição                              |
|----------------------|-------------|----------------|----------------------------------------|
| `IMTBL_API_KEY`      | Sim         | —              | Chave de API do ImmutableLog           |
| `IMTBL_SERVICE_NAME` | Não         | `my-service`   | Nome do serviço exibido nos eventos    |
| `IMTBL_ENV`          | Não         | `production`   | Ambiente: `production`, `staging`, `development` |

---

## Configuração

```yaml
# src/main/resources/application.yml
immutablelog:
  api-key: ${IMTBL_API_KEY}
  service-name: ${IMTBL_SERVICE_NAME:my-service}
  env: ${IMTBL_ENV:production}
  url: https://api.immutablelog.com
```

---

## Como funciona

| Etapa                       | Descrição                                                                 |
|-----------------------------|---------------------------------------------------------------------------|
| `ServerFilterPhase.FIRST`   | Garante execução antes de todos os outros filtros da aplicação            |
| `Publishers.map()` reativo  | Intercepta a response sem bloquear a thread I/O do Netty                 |
| `request.getAttribute()`   | Atributos propagados pelo contexto reativo — thread-safe                  |
| `HttpClient.sendAsync()`    | Fire-and-forget — nunca bloqueia a response ao cliente                   |

---

## Nome de evento customizado

```java
@Controller("/payments")
public class PaymentController {

    @Post
    public Payment create(HttpRequest<?> request, @Body PaymentDto dto) {
        request.setAttribute("imtbl.eventName", "payment.created");
        // ... lógica de negócio ...
        return payment;
    }
}
```

---

## ExceptionHandler para erros não tratados

```java
@Produces
@Singleton
@Requires(classes = {Exception.class, ExceptionHandler.class})
public class ImmutableLogExceptionHandler
    implements ExceptionHandler<Exception, HttpResponse<?>> {

    private final ImmutableLogFilter filter;

    public ImmutableLogExceptionHandler(ImmutableLogFilter filter) {
        this.filter = filter;
    }

    @Override
    public HttpResponse<?> handle(HttpRequest request, Exception ex) {
        String requestId = UUID.randomUUID().toString();
        long latencyMs = 0;
        filter.emitError(request, requestId, latencyMs, ex);
        return HttpResponse.serverError(Map.of("error", ex.getMessage()));
    }
}
```

---

## Payload enviado

```json
{
  "payload": "{\"method\":\"POST\",\"path\":\"/payments\",\"status\":200,\"latency_ms\":18,\"client_ip\":\"1.2.3.4\",\"user_agent\":\"...\"}",
  "meta": {
    "type": "success",
    "event_name": "payment.created",
    "service": "my-service",
    "request_id": "uuid",
    "env": "production",
    "environment": "production",
    "client_ip": "1.2.3.4",
    "http_method": "POST",
    "http_status": "200",
    "http_route": "/payments",
    "log_level": "info"
  }
}
```

> `env` é mantido por compatibilidade; `environment` é o nome canônico do contrato
> (`CONTRACT.md` §4.1 do `immutablelog_siem`). `client_ip`, `error_type` e `error_message`
> só aparecem quando o valor existe (IP resolvido, exceção capturada).

---

## Comportamento

| Situação                | Resultado                                               |
|-------------------------|---------------------------------------------------------|
| Path em `SKIP_PATHS`    | Evento ignorado, chain.proceed() chamado normalmente    |
| Status 2xx              | `type: success`                                         |
| Status 3xx              | `type: info`                                            |
| Status 4xx / 5xx        | `type: error`                                           |
| Falha no `sendAsync`    | Exceção silenciada — nunca quebra a aplicação           |
| GraalVM native          | Compatível — Micronaut AOT gera hints automaticamente   |
