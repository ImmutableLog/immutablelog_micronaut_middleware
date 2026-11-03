# ImmutableLog Micronaut Filter

Filtro Micronaut que captura automaticamente todas as requisições HTTP, exceto os caminhos que começam com `/health` ou `/ping` (ver [Exclusão de paths](#exclusão-de-paths)), e envia eventos de auditoria imutáveis para o ImmutableLog.

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

**Requisitos:** Java 11+ (o filtro usa `java.net.http.HttpClient`) e uma versão do Micronaut com `jakarta.inject` (o filtro importa `jakarta.inject.Singleton`, não `javax.inject`), ou seja, Micronaut 3 ou 4. O Micronaut 4 exige Java 17+.

---

## Adicionar ao projeto

Copie `ImmutableLogFilter.java` para dentro do seu projeto, por exemplo:

```
src/main/java/com/example/filter/ImmutableLogFilter.java
```

O arquivo **não tem declaração `package`** (ele começa direto nos `import`). Ao copiar, acrescente na primeira linha o pacote da pasta onde ele ficou:

```java
package com.example.filter;
```

A anotação `@Filter("/**")` registra o filtro globalmente — sem configuração extra. Depois de copiar, confira que o filtro foi registrado: faça uma requisição a um endpoint da aplicação e verifique se o evento chegou ao ImmutableLog.

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

## Exclusão de paths

A lista é fixa no código e a comparação é **por prefixo** (`startsWith`) sobre `request.getPath()`:

```java
private static final Set<String> SKIP_PATHS = Set.of("/health", "/ping");
```

Por ser prefixo, rotas como `/healthcare` ou `/pingback` também deixam de ser auditadas. Para mudar a lista, edite `SKIP_PATHS` no arquivo copiado.

---

## ExceptionHandler para erros não tratados

Você **não precisa** de um `ExceptionHandler` para auditar erros: uma exceção não tratada (lançada no controller ou devolvida como erro no `Publisher`) vira uma resposta `500` antes de chegar ao filtro, e o filtro registra esse `500` como qualquer outra resposta, com `type: error` (verificado no Micronaut 3.10; no Micronaut 4, confira com uma requisição de teste).

Se a aplicação quiser um handler próprio (para controlar o corpo da resposta), **não chame `filter.emitError()` dentro dele**: o filtro já vai registrar a resposta `500` que o handler devolver, e a mesma falha vira **dois eventos** com `request_id` diferentes. E não devolva `ex.getMessage()` ao cliente: a mensagem da exceção pode trazer detalhe interno (e, se for `null`, `Map.of` lança `NullPointerException` dentro do handler).

```java
@Produces
@Singleton
@Requires(classes = {Exception.class, ExceptionHandler.class})
public class AppExceptionHandler
    implements ExceptionHandler<Exception, HttpResponse<?>> {

    private static final Logger LOG = LoggerFactory.getLogger(AppExceptionHandler.class);

    @Override
    public HttpResponse<?> handle(HttpRequest request, Exception ex) {
        // Um handler próprio substitui o log padrão do Micronaut: registre a exceção aqui.
        LOG.error("Unhandled error on {} {}", request.getMethodName(), request.getPath(), ex);
        // O ImmutableLogFilter registra este 500; não chame emitError() aqui.
        return HttpResponse.serverError(Map.of("error", "Internal Server Error"));
    }
}
```

`Logger`/`LoggerFactory` são do `org.slf4j`, que já vem com o Micronaut. O evento desse `500` não leva `error_type`/`error_message`: hoje o filtro não oferece um jeito de anexar a exceção ao evento da resposta sem gerar um segundo evento.

> **Cópias antigas deste exemplo continuam afetadas até serem trocadas.** O handler publicado antes chamava `emitError()` e devolvia `ex.getMessage()`: cada falha gera dois eventos, a mensagem da exceção vai para o cliente, o stack trace não aparece no log da aplicação e, com mensagem `null`, o próprio handler falha.

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
> (`CONTRACT.md` §4.1 do `immutablelog_siem`). `client_ip` só aparece quando o valor existe.
> `error_type`, `error_message` e `error` no payload só aparecem no evento enviado por `emitError()`, que usa status `500`; o evento montado pelo próprio filtro nunca leva exceção (ver [ExceptionHandler](#exceptionhandler-para-erros-não-tratados) sobre por que não chamar `emitError()` num handler HTTP).
> Quando o body da requisição está disponível como `byte[]`, o payload também leva `request_body_hash` (SHA-256 do body, nunca o conteúdo).

---

## Comportamento

| Situação                | Resultado                                               |
|-------------------------|---------------------------------------------------------|
| Path em `SKIP_PATHS`    | Evento ignorado, chain.proceed() chamado normalmente    |
| Status 2xx              | `type: success`                                         |
| Status 3xx              | `type: info`                                            |
| Status 4xx / 5xx        | `type: error`                                           |
| Exceção não tratada     | Resposta `500` registrada pelo filtro (`type: error`), um evento só |
| Falha no `sendAsync`    | Exceção silenciada — nunca quebra a aplicação           |
| GraalVM native          | Compatível — Micronaut AOT gera hints automaticamente   |

---

## Limitações conhecidas

- **`client_ip` vem de header, sem validação.** O filtro usa o primeiro item de `X-Forwarded-For`; sem ele, `X-Real-IP`; sem os dois, o endereço remoto da conexão. Nenhum dos headers é validado, então quem chama a aplicação diretamente (sem um proxy que sobrescreva esses headers) pode forjar o IP registrado.
- **Sem endereço remoto resolvido, o evento sai sem IP.** Se não houver header de IP e o endereço remoto vier nulo ou não resolvido, o payload leva `"client_ip": null` e `meta.client_ip` é omitido. **Cópias do `ImmutableLogFilter.java` feitas antes desta correção continuam afetadas até serem recopiadas:** nesse cenário elas lançam `NullPointerException` dentro do `try` do envio e o evento inteiro se perde, sem log.
- **`Idempotency-Key` é um UUID aleatório gerado a cada evento**, o mesmo valor de `meta.request_id` (no filtro, gerado por requisição; no `emitError()`, é o `requestId` que quem chama passar). Não vem de nenhum header da requisição de entrada.
- **Entrega sem garantia.** Um único `sendAsync()` por evento: sem timeout configurado, sem retry, sem fila. A resposta do ImmutableLog é descartada e qualquer erro (rede, status 4xx/5xx, falha ao montar o evento) é ignorado em silêncio — o evento simplesmente se perde.
- **Sem suporte a trilha (`immutable_trail`):** o filtro não envia nenhum campo de trilha.
- **Chaves reservadas enviadas em `meta`:** `service`, `environment` (e `env`), `http_method`, `http_status`, `http_route` (template da rota quando o router resolveu, senão o caminho concreto) e `log_level` sempre; `client_ip` quando resolvido; `error_type` e `error_message` só via `emitError()`. Todos são strings.
- **`status`** no payload é o código HTTP numérico da resposta (ou `500` no `emitError()`); `meta.http_status` é o mesmo valor como string.
- **O caminho concreto vai no evento.** `path` no payload e o `event_name` padrão usam `request.getPath()` (sem query string), com os IDs que estiverem na URL. O evento selado não pode ser apagado depois.
- **O hash do body é SHA-256 sem sal.** Não expõe o conteúdo, mas um body previsível pode ser confirmado comparando o hash.
