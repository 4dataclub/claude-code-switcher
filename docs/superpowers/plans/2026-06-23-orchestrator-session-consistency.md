# Orchestrator-Session-Konsistenz Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die laufende Claude-Code-Session ist für **jeden** Pool exakt das oberste aktivierte Modell von `orchestrator-{pool}` — cloud/free ebenso wie local (wo Opus echt verschwindet und die Session über ccr→Ollama läuft).

**Architecture:** Zwei Eingriffe an der bestehenden Pin-Logik. **Teil 1 (cloud/free):** `pinOrchestratorForPool` liest künftig das Cascade-Top statt hart `anthropic` zu pinnen — anthropic-Top ⇒ Session direkt (mit gesetztem `cfg.model`), google/openrouter-Top ⇒ Session über den ccr-Router. **Teil 2 (local/Phase E):** Der ccr-Router bekommt einen Ollama-Provider; der local-Pin routet die Session über ccr→Ollama auf das `orchestrator-local`-Top und triggert den Wrapper-Restart. Local-Router-Config enthält **ausschließlich** Ollama (fail-closed, kein Cloud-Fallback).

**Tech Stack:** Java 17 / Spring Boot, Jackson (`ObjectNode`/`ArrayNode`), JUnit 5 + Mockito + AssertJ. ccr (claude-code-router) als Session-Proxy auf `:3456`, Ollama (OpenAI-kompatible API) als neuer ccr-Provider.

## Global Constraints

- **TDD strikt:** failing test → minimal code → green → commit. Pro Task eigener Commit.
- **Test-Kommando (Backend):** `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=<Klasse>#<Methode>` (ganze Klasse: `-Dtest=<Klasse>`; alles: `mvn -q test`).
- **Paket:** `com.dataclub.switcher` — Controller `controller/ApiController.java`, Service `service/RouterService.java`, Tests gespiegelt unter `src/test/...`.
- **Konsistenz-Invariante (Djavids Leitregel):** Session-Modell == oberstes aktiviertes Modell von `orchestrator-{pool}`. Gilt für alle Pools. Kein versteckter Opus-Pin.
- **Local fail-closed (Sicherheit, hart):** In local enthält die Router-Config NUR den Ollama-Provider — **kein** google/openrouter, **kein** Cloud-Fallback, egal ob Keys vorhanden sind. Kein aktiviertes lokales Modell ⇒ `localOrchestratorPending=true`, **kein** Cloud-Ausweich, Session bleibt stehen.
- **Keys nie anfassen:** `app_settings`/API-Keys werden nicht geschrieben. Reseeds/DB bleiben außen vor (dieser Plan ändert nur Code + Doku).
- **Bestehende Konstanten wiederverwenden:** `HOST_ROUTER_URL = "http://localhost:3456"`, `CASCADE_TO_SWITCHER = {gemini→google, anthropic→anthropic, openrouter→openrouter}`, Router-Platzhaltermodell `"claude-sonnet-4-5-20250929"`, ccr-Dummy-Key `"sk-ccr-anything"`.

---

## File Structure

- `java-backend/src/main/java/com/dataclub/switcher/controller/ApiController.java` — `orchestratorTopModel` (neu), Pin-Helfer `envOf`/`pinAnthropicDirect`/`pinViaRouter` (neu), `pinOrchestratorForPool` (cloud/free + local neu), `setMode` (Router-Restart-Wiring), `whoami` (Ollama-Zweig). `hasEnabledLocalOrchestrator` entfällt.
- `java-backend/src/main/java/com/dataclub/switcher/service/RouterService.java` — `UI_TO_CCR` (+ollama), `ollamaBaseUrl` (neu), `buildOllamaProvider` (neu), `buildProvidersForPool` (neu), `writeRouterConfig` (pool-bewusst).
- `java-backend/src/test/java/com/dataclub/switcher/controller/ApiControllerTest.java` — neue Tests für Top-Helfer + Pin (cloud/free/local) + Restart-Wiring + whoami-Ollama.
- `java-backend/src/test/java/com/dataclub/switcher/service/RouterServiceTest.java` — neue Tests für Ollama-Provider + local-only-Config.
- `agents/orchestrator-check.md` — read-only Konsistenz-Verifikations-Agent (neu), Deploy nach `~/.claude/agents/`.
- `docker-compose.yml` — Verifikation Router→Ollama-Erreichbarkeit (ggf. Netz-Fix).
- `SUPERMODELL.md`, `~/Dokumente/brain/02 Projekte/Claude Code Switcher.md`, Spec-Status — Doku-Update (Phase E erledigt).

---

## Teil 1 — cloud/free: Session = Cascade-Top

### Task 1: Helper `orchestratorTopModel(pool)`

**Files:**
- Modify: `java-backend/src/main/java/com/dataclub/switcher/controller/ApiController.java`
- Test: `java-backend/src/test/java/com/dataclub/switcher/controller/ApiControllerTest.java`

**Interfaces:**
- Consumes: `modelSvc.listModels()` → `List<AiModelConfig>`; `AiModelConfig.getCategory()/getEnabled()/getOrderIdx()/getProvider()/getModelId()`.
- Produces: `AiModelConfig orchestratorTopModel(String pool)` — oberstes (kleinster `orderIdx`) aktiviertes Modell der `orchestrator-{pool}`-Zelle, oder `null` wenn keins. **Filtert NICHT auf Cloud-Provider** (anders als `orchestratorFailoverChain`), weil local/ollama hier ein legitimes Session-Ziel ist.

- [ ] **Step 1: Write the failing tests**

In `ApiControllerTest.java`, nach dem `orchestratorChain_*`-Block einfügen:

```java
// ════════════════════════════════════════════════════════════════════════
//  orchestratorTopModel — oberstes aktiviertes Modell der Zelle (Session-Ziel)
// ════════════════════════════════════════════════════════════════════════

@Test
void topModel_picksLowestOrderIdxEnabled() {
    when(modelSvc.listModels()).thenReturn(List.of(
            model("gemini", "gemini-2.5-flash", "orchestrator-cloud", true, 5),
            model("anthropic", "claude-sonnet-4-6", "orchestrator-cloud", true, 1)
    ));
    AiModelConfig top = controller.orchestratorTopModel("cloud");
    assertThat(top).isNotNull();
    assertThat(top.getModelId()).isEqualTo("claude-sonnet-4-6");
}

@Test
void topModel_skipsDisabled() {
    when(modelSvc.listModels()).thenReturn(List.of(
            model("anthropic", "claude-opus-4-7", "orchestrator-cloud", false, 0), // disabled → übersprungen
            model("anthropic", "claude-sonnet-4-6", "orchestrator-cloud", true, 1)
    ));
    assertThat(controller.orchestratorTopModel("cloud").getModelId()).isEqualTo("claude-sonnet-4-6");
}

@Test
void topModel_nullWhenEmpty() {
    when(modelSvc.listModels()).thenReturn(List.of());
    assertThat(controller.orchestratorTopModel("cloud")).isNull();
}

@Test
void topModel_local_returnsOllama_notSkipped() {
    // Anders als die Failover-Kette: local/ollama ist hier ein gültiges Session-Ziel.
    when(modelSvc.listModels()).thenReturn(List.of(
            model("ollama", "qwen2.5-coder:7b", "orchestrator-local", true, 0)
    ));
    AiModelConfig top = controller.orchestratorTopModel("local");
    assertThat(top).isNotNull();
    assertThat(top.getProvider()).isEqualTo("ollama");
    assertThat(top.getModelId()).isEqualTo("qwen2.5-coder:7b");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=ApiControllerTest#topModel_picksLowestOrderIdxEnabled+topModel_skipsDisabled+topModel_nullWhenEmpty+topModel_local_returnsOllama_notSkipped`
Expected: FAIL — `cannot find symbol: method orchestratorTopModel`.

- [ ] **Step 3: Write minimal implementation**

In `ApiController.java`, direkt **über** `orchestratorFailoverChain` einfügen:

```java
/**
 * Oberstes aktiviertes Modell der {@code orchestrator-{pool}}-Zelle (kleinster
 * orderIdx) = das Session-Modell (Konsistenz-Invariante). Anders als
 * {@link #orchestratorFailoverChain} wird hier NICHT auf Cloud-Provider gefiltert —
 * local/ollama ist als Session-Ziel legitim (Phase E). {@code null} = leere Zelle.
 */
AiModelConfig orchestratorTopModel(String pool) {
    String cat = "orchestrator-" + pool;
    return modelSvc.listModels().stream()
        .filter(m -> cat.equals(m.getCategory()) && Boolean.TRUE.equals(m.getEnabled()))
        .min(java.util.Comparator.comparingInt(
            m -> m.getOrderIdx() == null ? Integer.MAX_VALUE : m.getOrderIdx()))
        .orElse(null);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=ApiControllerTest`
Expected: PASS (alle ApiControllerTest grün).

- [ ] **Step 5: Commit**

```bash
cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher
git add java-backend/src/main/java/com/dataclub/switcher/controller/ApiController.java java-backend/src/test/java/com/dataclub/switcher/controller/ApiControllerTest.java
git commit -m "feat(orchestrator): add orchestratorTopModel helper (session = cascade top)"
```

---

### Task 2: cloud/free-Pin routet auf das Cascade-Top

**Files:**
- Modify: `java-backend/src/main/java/com/dataclub/switcher/controller/ApiController.java:552-575` (`pinOrchestratorForPool` cloud/free-Zweig) + neue private Helfer.
- Test: `java-backend/src/test/java/com/dataclub/switcher/controller/ApiControllerTest.java`

**Interfaces:**
- Consumes: `orchestratorTopModel(pool)` (Task 1), `CASCADE_TO_SWITCHER`, `HOST_ROUTER_URL`.
- Produces: private `void envOf`-Helfer ist intern; nach außen sichtbares Verhalten über `setMode` testbar — cloud/free pinnt die Session auf das Top-Modell. anthropic-Top ⇒ `cfg.model` gesetzt, kein `ANTHROPIC_BASE_URL`, `sw.provider=anthropic`, kein `activeRoute`. google/openrouter-Top ⇒ `ANTHROPIC_BASE_URL=HOST_ROUTER_URL`, `sw.activeRoute={provider,model}`, `sw.provider=<swProv>`.

- [ ] **Step 1: Write the failing tests**

In `ApiControllerTest.java`, im `setMode`-Block ergänzen:

```java
@Test
void setMode_cloud_anthropicTop_pinsSessionToThatModel_direct() {
    ObjectNode cfg = M.createObjectNode();
    when(configs.readConfig()).thenReturn(cfg);
    when(modelSvc.listModels()).thenReturn(List.of(
            model("anthropic", "claude-sonnet-4-6", "orchestrator-cloud", true, 0)
    ));
    ApiController.ModeRequest req = new ApiController.ModeRequest();
    req.pool = "cloud"; req.supermodel = true;
    controller.setMode(req);

    ObjectNode sw = (ObjectNode) cfg.get("_switcher");
    // Session-Modell == Cascade-Top (vorher: Modell entfernt → claude-Binary-Default opus)
    assertThat(cfg.path("model").asText()).isEqualTo("claude-sonnet-4-6");
    assertThat(sw.path("provider").asText()).isEqualTo("anthropic");
    assertThat(sw.has("activeRoute")).isFalse();            // anthropic = direkt, kein Router
    assertThat(cfg.path("env").has("ANTHROPIC_BASE_URL")).isFalse();
}

@Test
void setMode_cloud_googleTop_pinsSessionViaRouter() {
    ObjectNode cfg = M.createObjectNode();
    when(configs.readConfig()).thenReturn(cfg);
    when(modelSvc.listModels()).thenReturn(List.of(
            model("gemini", "gemini-2.5-pro", "orchestrator-cloud", true, 0)
    ));
    ApiController.ModeRequest req = new ApiController.ModeRequest();
    req.pool = "cloud"; req.supermodel = true;
    controller.setMode(req);

    ObjectNode sw = (ObjectNode) cfg.get("_switcher");
    assertThat(cfg.path("env").path("ANTHROPIC_BASE_URL").asText()).isEqualTo("http://localhost:3456");
    assertThat(sw.path("provider").asText()).isEqualTo("google");
    assertThat(sw.path("activeRoute").path("provider").asText()).isEqualTo("google");
    assertThat(sw.path("activeRoute").path("model").asText()).isEqualTo("gemini-2.5-pro");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=ApiControllerTest#setMode_cloud_anthropicTop_pinsSessionToThatModel_direct+setMode_cloud_googleTop_pinsSessionViaRouter`
Expected: FAIL — `setMode_cloud_anthropicTop...` erwartet `cfg.model=sonnet` aber Pin entfernt das Modell (Default opus); `setMode_cloud_googleTop...` erwartet `activeRoute`/`BASE_URL`, der alte Pin setzt aber hart anthropic.

- [ ] **Step 3: Write minimal implementation**

In `ApiController.java` neue private Helfer einfügen (z.B. direkt über `pinOrchestratorForPool`):

```java
private ObjectNode envOf(ObjectNode cfg) {
    return cfg.has("env") && cfg.get("env").isObject()
        ? (ObjectNode) cfg.get("env") : configs.mapper().createObjectNode();
}

/** Session direkt an Anthropic (kein Router), Modell = das Cascade-Top. */
private void pinAnthropicDirect(ObjectNode cfg, ObjectNode sw, String model) {
    ObjectNode env = envOf(cfg);
    env.remove("ANTHROPIC_API_KEY");
    env.remove("ANTHROPIC_BASE_URL");
    cfg.set("env", env);
    if (model != null && !model.isBlank()) cfg.put("model", model); else cfg.remove("model");
    sw.put("provider", "anthropic");
    sw.remove("activeRoute");
}

/** Session über den ccr-Router (BASE_URL→:3456), Route = swProvider,model. */
private void pinViaRouter(ObjectNode cfg, ObjectNode sw, String swProvider, String model) {
    ObjectNode env = envOf(cfg);
    env.put("ANTHROPIC_API_KEY", "sk-ccr-anything");
    env.put("ANTHROPIC_BASE_URL", HOST_ROUTER_URL);
    cfg.set("env", env);
    cfg.put("model", "claude-sonnet-4-5-20250929"); // ccr-Platzhalter, Route entscheidet
    ObjectNode ar = configs.mapper().createObjectNode();
    ar.put("provider", swProvider); ar.put("model", model);
    sw.set("activeRoute", ar);
    sw.put("provider", swProvider);
}
```

Den cloud/free-Zweig von `pinOrchestratorForPool` (alles ab `// cloud/free → Opus pinnen` bis zum letzten `return false;`) ersetzen durch:

```java
        // cloud/free → Session = oberstes aktiviertes Modell der orchestrator-{pool}-Zelle
        sw.remove("localOrchestratorPending");
        AiModelConfig top = orchestratorTopModel(pool);
        if (top == null) {
            // Sicherheitsnetz: leere Zelle → Anthropic-direkt (Opus-Default), wie bisher.
            if (!"anthropic".equals(sw.path("provider").asText(""))) {
                pinAnthropicDirect(cfg, sw, null);
                sw.put("chain_position", 0);
                return true;
            }
            return false;
        }
        String swProv = CASCADE_TO_SWITCHER.getOrDefault(top.getProvider(), top.getProvider());
        if ("anthropic".equals(swProv)) {
            boolean changed = !"anthropic".equals(sw.path("provider").asText(""))
                || !top.getModelId().equals(cfg.path("model").asText(""));
            pinAnthropicDirect(cfg, sw, top.getModelId());
            sw.put("chain_position", 0);
            return changed;
        }
        // google/openrouter → Session via ccr-Router auf das Top-Modell
        boolean changed = !swProv.equals(sw.path("provider").asText(""))
            || !top.getModelId().equals(sw.path("activeRoute").path("model").asText(""));
        pinViaRouter(cfg, sw, swProv, top.getModelId());
        sw.put("chain_position", 0);
        return changed;
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=ApiControllerTest`
Expected: PASS (inkl. der bestehenden `setMode_cloudSupermodel_isAuto_armsChainFromCell`, die `cfg.model` nicht prüft).

- [ ] **Step 5: Commit**

```bash
cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher
git add java-backend/src/main/java/com/dataclub/switcher/controller/ApiController.java java-backend/src/test/java/com/dataclub/switcher/controller/ApiControllerTest.java
git commit -m "feat(orchestrator): cloud/free pin routes session to cascade top model"
```

---

## Teil 2 — local (Phase E): Session läuft über ccr→Ollama

### Task 3: Ollama als ccr-Router-Provider

**Files:**
- Modify: `java-backend/src/main/java/com/dataclub/switcher/service/RouterService.java` (`UI_TO_CCR`, neues Feld `ollamaBaseUrl`, `buildOllamaProvider`, `buildProvidersForPool`).
- Test: `java-backend/src/test/java/com/dataclub/switcher/service/RouterServiceTest.java`

**Interfaces:**
- Produces: `ObjectNode buildOllamaProvider(String model)` — ccr-Provider `{name:"ollama", api_base_url:<ollamaBaseUrl>, api_key:"ollama", models:[model], transformer:{use:["openai"]}}`. `ArrayNode buildProvidersForPool(String pool, ObjectNode keys, String localModel)` — local ⇒ NUR Ollama (fail-closed); cloud/free ⇒ `buildProviders(keys)`.
- Konstante: `UI_TO_CCR` enthält `ollama→ollama`.

- [ ] **Step 1: Write the failing tests**

In `RouterServiceTest.java` ergänzen:

```java
@Test
void buildOllamaProvider_openaiTransformer_modelListed() {
    ObjectNode p = router.buildOllamaProvider("qwen2.5-coder:7b");
    assertThat(p.path("name").asText()).isEqualTo("ollama");
    assertThat(p.path("api_base_url").asText()).contains("11434");
    assertThat(p.path("transformer").path("use").get(0).asText()).isEqualTo("openai");
    assertThat(p.path("models").get(0).asText()).isEqualTo("qwen2.5-coder:7b");
}

@Test
void buildProvidersForPool_local_onlyOllama_ignoresCloudKeys_failClosed() {
    ObjectNode keys = M.createObjectNode();
    keys.put("google", "AIza-valid"); keys.put("openrouter", "sk-or-valid");
    var providers = router.buildProvidersForPool("local", keys, "qwen2.5-coder:7b");
    // FAIL-CLOSED: trotz Cloud-Keys NUR Ollama, kein gemini/openrouter.
    assertThat(providers).hasSize(1);
    assertThat(providers.get(0).path("name").asText()).isEqualTo("ollama");
}

@Test
void buildProvidersForPool_cloud_usesCloudProviders() {
    ObjectNode keys = M.createObjectNode();
    keys.put("google", "AIza-valid");
    var providers = router.buildProvidersForPool("cloud", keys, null);
    assertThat(providers.get(0).path("name").asText()).isEqualTo("gemini");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=RouterServiceTest`
Expected: FAIL — `cannot find symbol: method buildOllamaProvider / buildProvidersForPool`.

- [ ] **Step 3: Write minimal implementation**

In `RouterService.java`:

`UI_TO_CCR` um Ollama erweitern:

```java
    private static final Map<String, String> UI_TO_CCR = new HashMap<>() {{
        put("google", "gemini");
        put("anthropic", "anthropic");
        put("openrouter", "openrouter");
        put("ollama", "ollama");
    }};
```

Neues Feld neben `routerContainer` (Default-Initializer, damit Unit-Tests ohne Spring funktionieren; `@Value` überschreibt im Container):

```java
    @Value("${switcher.ollama.baseUrl:http://ollama:11434/v1/chat/completions}")
    private String ollamaBaseUrl = "http://ollama:11434/v1/chat/completions";
```

Neue Methoden (z.B. direkt nach `buildProviders`):

```java
    /** ccr-Provider für lokales Ollama (OpenAI-kompatible API, Key ist Dummy). */
    ObjectNode buildOllamaProvider(String model) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name", "ollama");
        p.put("api_base_url", ollamaBaseUrl);
        p.put("api_key", "ollama"); // Ollama ignoriert den Key, ccr verlangt aber einen
        ArrayNode m = p.putArray("models");
        if (model != null && !model.isBlank()) m.add(model);
        p.set("transformer", mapper.createObjectNode().set("use", mapper.createArrayNode().add("openai")));
        return p;
    }

    /**
     * Provider-Liste je Pool. <b>local = NUR Ollama (fail-closed)</b> — kein google/
     * openrouter, egal ob Keys da sind, nichts verlässt das interne Netz. cloud/free =
     * {@link #buildProviders} wie gehabt.
     */
    ArrayNode buildProvidersForPool(String pool, ObjectNode keys, String localModel) {
        if ("local".equals(pool)) {
            ArrayNode out = mapper.createArrayNode();
            out.add(buildOllamaProvider(localModel));
            return out;
        }
        return buildProviders(keys);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=RouterServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher
git add java-backend/src/main/java/com/dataclub/switcher/service/RouterService.java java-backend/src/test/java/com/dataclub/switcher/service/RouterServiceTest.java
git commit -m "feat(router): add ollama provider + pool-aware provider selection (local fail-closed)"
```

---

### Task 4: `writeRouterConfig` pool-bewusst (local = nur Ollama)

**Files:**
- Modify: `java-backend/src/main/java/com/dataclub/switcher/service/RouterService.java:120-178` (`writeRouterConfig`).
- Test: `java-backend/src/test/java/com/dataclub/switcher/service/RouterServiceTest.java`

**Interfaces:**
- Consumes: `buildProvidersForPool` (Task 3), `configs.getSwitcher()` (`pool`, `activeRoute`/`fallback_chain`), `configs.routerConfigPath()`.
- Produces: geschriebene `router-config.json` — bei `pool=local` enthält `Providers` NUR `ollama` und `Router.default == "ollama,<localModel>"`.

- [ ] **Step 1: Write the failing test**

In `RouterServiceTest.java` ergänzen (mit `@TempDir`):

```java
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

@Test
void writeRouterConfig_localPool_writesOnlyOllamaRoute(@TempDir Path tmp) throws Exception {
    Path cfgFile = tmp.resolve("router-config.json");
    when(configs.routerConfigPath()).thenReturn(cfgFile.toString());
    // Local-Pool + Route auf das lokale Modell, dazu vorhandene Cloud-Keys in der DB.
    ObjectNode sw = M.createObjectNode();
    sw.put("pool", "local");
    sw.putObject("activeRoute").put("provider", "ollama").put("model", "qwen2.5-coder:7b");
    when(configs.getSwitcher()).thenReturn(sw);
    when(modelSvc.getSettingRaw("geminiApiKey")).thenReturn("AIza-valid");
    when(modelSvc.getSettingRaw("openrouterApiKey")).thenReturn("sk-or-valid");

    router.writeRouterConfig();

    JsonNode out = M.readTree(cfgFile.toFile());
    // FAIL-CLOSED: genau ein Provider, und der ist Ollama — kein gemini/openrouter.
    assertThat(out.path("Providers")).hasSize(1);
    assertThat(out.path("Providers").get(0).path("name").asText()).isEqualTo("ollama");
    assertThat(out.path("Router").path("default").asText()).isEqualTo("ollama,qwen2.5-coder:7b");
}
```

Falls noch nicht importiert, oben in der Testklasse `import com.fasterxml.jackson.databind.JsonNode;` ergänzen.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=RouterServiceTest#writeRouterConfig_localPool_writesOnlyOllamaRoute`
Expected: FAIL — alte `writeRouterConfig` ruft `buildProviders(keys)` auf → schreibt gemini/openrouter, `Providers` size ≠ 1 (bzw. enthält kein ollama).

- [ ] **Step 3: Write minimal implementation**

In `writeRouterConfig`, nach `ObjectNode sw = configs.getSwitcher();` den Pool lesen:

```java
        String pool = sw.path("pool").asText("cloud");
```

Und die Zeile `ArrayNode providers = buildProviders(keys);` ersetzen durch:

```java
        ArrayNode providers = buildProvidersForPool(pool, keys, routeModel);
```

(`routeProvider`/`routeModel` werden oberhalb bereits ermittelt; für local liefert `activeRoute` `ollama`/`<model>`, `mappedProvider = UI_TO_CCR.get("ollama") = "ollama"`, `defaultRoute = "ollama,<model>"`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=RouterServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher
git add java-backend/src/main/java/com/dataclub/switcher/service/RouterService.java java-backend/src/test/java/com/dataclub/switcher/service/RouterServiceTest.java
git commit -m "feat(router): writeRouterConfig is pool-aware (local writes ollama-only config)"
```

---

### Task 5: local-Pin routet die Session über ccr→Ollama

**Files:**
- Modify: `java-backend/src/main/java/com/dataclub/switcher/controller/ApiController.java:552-585` (`pinOrchestratorForPool` local-Zweig; `hasEnabledLocalOrchestrator` entfernen).
- Test: `java-backend/src/test/java/com/dataclub/switcher/controller/ApiControllerTest.java`

**Interfaces:**
- Consumes: `orchestratorTopModel("local")` (Task 1), `pinViaRouter` (Task 2).
- Produces: local mit aktiviertem Modell ⇒ `sw.activeRoute={ollama,<model>}`, `env.ANTHROPIC_BASE_URL=:3456`, `localOrchestratorPending` entfernt, `return true` (Restart). Kein aktiviertes Modell ⇒ `localOrchestratorPending=true`, `return false`, kein Reroute.

- [ ] **Step 1: Write the failing tests**

In `ApiControllerTest.java`, im `setMode`-Block ergänzen:

```java
@Test
void setMode_local_withEnabledModel_pinsSessionViaOllama_restart() {
    ObjectNode cfg = M.createObjectNode();
    when(configs.readConfig()).thenReturn(cfg);
    when(modelSvc.listModels()).thenReturn(List.of(
            model("ollama", "qwen2.5-coder:7b", "orchestrator-local", true, 0)
    ));
    ApiController.ModeRequest req = new ApiController.ModeRequest();
    req.pool = "local"; req.supermodel = true;
    var resp = controller.setMode(req);

    ObjectNode sw = (ObjectNode) cfg.get("_switcher");
    // Opus verschwindet: Session läuft über ccr→Ollama auf dem lokalen Modell.
    assertThat(sw.path("activeRoute").path("provider").asText()).isEqualTo("ollama");
    assertThat(sw.path("activeRoute").path("model").asText()).isEqualTo("qwen2.5-coder:7b");
    assertThat(cfg.path("env").path("ANTHROPIC_BASE_URL").asText()).isEqualTo("http://localhost:3456");
    assertThat(sw.path("localOrchestratorPending").asBoolean(false)).isFalse();
    assertThat(sw.path("mode").asText()).isEqualTo("manual"); // local bleibt fail-closed, kein auto
}

@Test
void setMode_local_noEnabledModel_failClosed_noReroute_pending() {
    ObjectNode cfg = M.createObjectNode();
    when(configs.readConfig()).thenReturn(cfg);
    when(modelSvc.listModels()).thenReturn(List.of()); // kein lokales Modell aktiv
    ApiController.ModeRequest req = new ApiController.ModeRequest();
    req.pool = "local"; req.supermodel = true;
    controller.setMode(req);

    ObjectNode sw = (ObjectNode) cfg.get("_switcher");
    assertThat(sw.path("localOrchestratorPending").asBoolean()).isTrue();
    assertThat(sw.has("activeRoute")).isFalse();               // KEIN Reroute
    assertThat(cfg.path("env").has("ANTHROPIC_BASE_URL")).isFalse(); // kein Cloud/Router-Ausweich
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=ApiControllerTest#setMode_local_withEnabledModel_pinsSessionViaOllama_restart+setMode_local_noEnabledModel_failClosed_noReroute_pending`
Expected: FAIL — `setMode_local_withEnabledModel...` erwartet `activeRoute=ollama`, der alte local-Zweig macht `return false` ohne Reroute.

- [ ] **Step 3: Write minimal implementation**

Den local-Zweig von `pinOrchestratorForPool` (`if ("local".equals(pool)) { ... return false; }`) ersetzen durch:

```java
        if ("local".equals(pool)) {
            // FAIL-CLOSED: NIE auf Cloud pinnen. Session läuft echt über ccr→Ollama
            // auf dem orchestrator-local-Top (Phase E) — Opus verschwindet.
            AiModelConfig localTop = orchestratorTopModel(pool);
            if (localTop == null) {
                // Kein aktiviertes lokales Modell → pending, KEIN Reroute, KEIN Cloud-Ausweich.
                sw.put("localOrchestratorPending", true);
                return false;
            }
            sw.remove("localOrchestratorPending");
            pinViaRouter(cfg, sw, "ollama", localTop.getModelId());
            sw.put("chain_position", 0);
            return true; // Restart: Wrapper zieht die Session aufs lokale Modell hoch
        }
```

`hasEnabledLocalOrchestrator()` (Z.577-585) ist danach ungenutzt → komplett entfernen.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=ApiControllerTest`
Expected: PASS (inkl. `setMode_localSupermodel_isManual_noCloudChainArmed_failClosed`: leere Modell-Liste → pending=true, kein activeRoute, mode=manual, kein fallback_chain).

- [ ] **Step 5: Commit**

```bash
cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher
git add java-backend/src/main/java/com/dataclub/switcher/controller/ApiController.java java-backend/src/test/java/com/dataclub/switcher/controller/ApiControllerTest.java
git commit -m "feat(orchestrator): local pin reroutes session via ccr->ollama (Phase E)"
```

---

### Task 6: `setMode` startet den ccr-Router neu, wenn die Session über ihn läuft

**Files:**
- Modify: `java-backend/src/main/java/com/dataclub/switcher/controller/ApiController.java:481-486` (`setMode`, Restart-Block).
- Test: `java-backend/src/test/java/com/dataclub/switcher/controller/ApiControllerTest.java`

**Interfaces:**
- Consumes: `sw.activeRoute` (von `pinViaRouter` gesetzt), `router.restartRouter()`.
- Produces: Wenn die Session über den Router geroutet wird (`activeRoute` vorhanden — local-Ollama oder cloud/free-google/openrouter-Top), wird `router.restartRouter()` aufgerufen, damit ccr die neue Config lädt. Anthropic-Direkt-Top (kein `activeRoute`) ⇒ kein Router-Restart.

- [ ] **Step 1: Write the failing tests**

In `ApiControllerTest.java` ergänzen:

```java
@Test
void setMode_routedSession_restartsRouter() {
    ObjectNode cfg = M.createObjectNode();
    when(configs.readConfig()).thenReturn(cfg);
    when(modelSvc.listModels()).thenReturn(List.of(
            model("ollama", "qwen2.5-coder:7b", "orchestrator-local", true, 0)
    ));
    ApiController.ModeRequest req = new ApiController.ModeRequest();
    req.pool = "local"; req.supermodel = true;
    controller.setMode(req);

    verify(router).restartRouter(); // ccr muss die neue (ollama-only) Config laden
}

@Test
void setMode_anthropicDirect_doesNotRestartRouter() {
    ObjectNode cfg = M.createObjectNode();
    when(configs.readConfig()).thenReturn(cfg);
    when(modelSvc.listModels()).thenReturn(List.of(
            model("anthropic", "claude-sonnet-4-6", "orchestrator-cloud", true, 0)
    ));
    ApiController.ModeRequest req = new ApiController.ModeRequest();
    req.pool = "cloud"; req.supermodel = true;
    controller.setMode(req);

    verify(router, never()).restartRouter(); // direkt = kein Router im Spiel
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=ApiControllerTest#setMode_routedSession_restartsRouter+setMode_anthropicDirect_doesNotRestartRouter`
Expected: `setMode_routedSession_restartsRouter` FAILS (restartRouter wird nie aufgerufen — setMode ruft nur `writeRouterConfig`).

- [ ] **Step 3: Write minimal implementation**

In `setMode` den Block ab `router.writeRouterConfig();` (Z.483) anpassen:

```java
        cfg.set("_switcher", sw);
        configs.writeConfig(cfg);
        router.writeRouterConfig();
        // Läuft die Session über den ccr-Router (local→ollama oder cloud/free google/
        // openrouter-Top), muss ccr die neue Config laden. Anthropic-direkt = kein Router.
        if (sw.has("activeRoute") && sw.get("activeRoute").isObject()) {
            router.restartRouter();
        }
        if (needRestart) {
            configs.writeRestartMarker("local".equals(pool) ? "supermodel-local" : "supermodel-on", null);
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test`
Expected: PASS (gesamte Suite grün).

- [ ] **Step 5: Commit**

```bash
cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher
git add java-backend/src/main/java/com/dataclub/switcher/controller/ApiController.java java-backend/src/test/java/com/dataclub/switcher/controller/ApiControllerTest.java
git commit -m "feat(orchestrator): restart ccr router when session is routed through it"
```

---

### Task 7: `whoami` meldet local/Ollama ehrlich

**Files:**
- Modify: `java-backend/src/main/java/com/dataclub/switcher/controller/ApiController.java:119-143` (`whoami`).
- Test: `java-backend/src/test/java/com/dataclub/switcher/controller/ApiControllerTest.java`

**Interfaces:**
- Consumes: `configs.deriveProvider(cfg)` (gibt nach local-Pin `"ollama"` zurück, weil `sw.provider="ollama"`), `cfg._switcher.activeRoute.model`.
- Produces: `whoami()` liefert bei provider=`ollama` eine ehrliche Zeile mit dem lokalen Modell statt fälschlich „Anthropic direkt / claude-sonnet-4-5-…". Diese Zeile ist die Observability-Quelle für den Verifikations-Agent (Task 10).

**Warum (kritisch):** Ohne diesen Zweig würde `whoami` bei local auf `cfg.model` (=Router-Platzhalter `claude-sonnet-4-5-20250929`) zurückfallen und „Claude Sonnet (Anthropic direkt)" melden — obwohl die Session real auf qwen via Ollama läuft. Das ist exakt die Divergenz, die dieser Plan beseitigt.

- [ ] **Step 1: Write the failing test**

In `ApiControllerTest.java` ergänzen:

```java
@Test
void whoami_localOllamaRoute_reportsLocalModel_notAnthropic() {
    ObjectNode cfg = M.createObjectNode();
    cfg.put("model", "claude-sonnet-4-5-20250929"); // Router-Platzhalter (darf NICHT durchschlagen)
    ObjectNode sw = cfg.putObject("_switcher");
    sw.put("provider", "ollama");
    sw.putObject("activeRoute").put("provider", "ollama").put("model", "qwen2.5-coder:7b");
    when(configs.readConfig()).thenReturn(cfg);
    when(configs.deriveProvider(cfg)).thenReturn("ollama");

    String who = controller.whoami();
    assertThat(who).contains("qwen2.5-coder:7b");
    assertThat(who).doesNotContain("Anthropic direkt");
    assertThat(who.toLowerCase()).contains("lokal"); // local/Ollama klar erkennbar
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=ApiControllerTest#whoami_localOllamaRoute_reportsLocalModel_notAnthropic`
Expected: FAIL — `whoami` fällt auf „… (Anthropic direkt) …" zurück (ollama nicht behandelt).

- [ ] **Step 3: Write minimal implementation**

In `whoami()`, direkt **vor** der Schluss-Zeile `String m = cfg.path("model")...` den Ollama-Zweig einfügen:

```java
        if ("ollama".equals(provider)) {
            String model = ar.path("model").asText("?");
            return model + " via Ollama (lokal) — läuft lokal, nichts verlässt das interne Netz";
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/java-backend && mvn -q test -Dtest=ApiControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher
git add java-backend/src/main/java/com/dataclub/switcher/controller/ApiController.java java-backend/src/test/java/com/dataclub/switcher/controller/ApiControllerTest.java
git commit -m "fix(whoami): report local ollama model truthfully (not Anthropic placeholder)"
```

---

### Task 8: Router→Ollama-Erreichbarkeit in docker-compose verifizieren

**Files:**
- Verify/Modify: `docker-compose.yml`

**Interfaces:**
- Produces: Garantie, dass der ccr-Router-Container den Ollama-Host unter `http://ollama:11434` erreicht (DNS via gemeinsames compose-Netz), passend zum `switcher.ollama.baseUrl`-Default aus Task 3.

- [ ] **Step 1: Netz-Zugehörigkeit prüfen**

Run: `cd /home/dmammadov/claude-switcher && docker compose config` und prüfen, dass `router` und `ollama` im selben Netzwerk hängen (oder beide am Default-Netz). Wenn `router` ein abweichendes `networks:`-Set hat, ohne das Netz von `ollama` → ergänzen.

- [ ] **Step 2: Erreichbarkeit live testen (sofern Stack läuft)**

Run: `docker exec claude-switcher-router-1 sh -c "wget -qO- http://ollama:11434/api/tags || echo UNREACHABLE"`
Expected: JSON mit Modellen (oder leeres `{"models":[]}`), NICHT `UNREACHABLE`. Bei `UNREACHABLE`: `ollama` zum Router-Netz hinzufügen in `docker-compose.yml`, `docker compose up -d` und erneut testen.

- [ ] **Step 3: Optional `switcher.ollama.baseUrl` überschreiben**

Falls der Ollama-Service-Name nicht `ollama` ist, im `switcher-backend`-Service eine Env-Var setzen: `SWITCHER_OLLAMA_BASEURL: http://<service>:11434/v1/chat/completions`. Sonst greift der Default aus Task 3.

- [ ] **Step 4: Commit (nur falls docker-compose.yml geändert wurde)**

```bash
cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher
git add docker-compose.yml
git commit -m "chore(compose): ensure ccr router can reach ollama host"
```

(Wenn keine Änderung nötig war: kein Commit, Task als verifiziert abhaken.)

---

### Task 9: Live-Integrationscheck — ccr treibt Ollama (manuell)

**Files:** keine — reiner Verifikationslauf gegen den laufenden Stack.

**Interfaces:**
- Consumes: das gebaute Backend (Tasks 1-6) + erreichbares Ollama (Task 7).
- Produces: Beleg, dass beim Umschalten auf local die Session real über ccr→Ollama läuft (kein Anthropic-Traffic).

- [ ] **Step 1: Backend neu bauen**

Run: `cd /home/dmammadov/claude-switcher && docker compose up -d --build switcher-backend`
Expected: Backend startet, Health grün. (Meine eigene Session hängt an Anthropic direkt → Rebuild trifft sie nicht.)

- [ ] **Step 2: Lokales Orchestrator-Modell ziehen + aktivieren**

Run: `docker exec claude-switcher-ollama-1 ollama pull qwen2.5-coder:7b`
Dann `orchestrator-local` in der UI (`http://localhost:2000`) aktivieren (oder DB-`enabled=true`).
Expected: `curl -s http://localhost:8091/api/categories` listet `orchestrator-local`; das Modell ist aktiv.

- [ ] **Step 3: Auf local schalten und Router-Config prüfen**

Run: `curl -sS -X POST http://localhost:2000/api/mode -H 'Content-Type: application/json' -d '{"pool":"local","supermodel":true}'`
Dann die geschriebene Config prüfen: `docker exec claude-switcher-router-1 cat /app/router-config.json` (Pfad ggf. anpassen).
Expected: `Providers` enthält NUR `ollama`; `Router.default == "ollama,qwen2.5-coder:7b"`. Response-`localOrchestratorPending=false`.

- [ ] **Step 4: Eine geroutete Anfrage gegen ccr fahren (Tool-Use/Streaming)**

Run: `curl -sS http://localhost:3456/v1/messages -H 'Content-Type: application/json' -H 'x-api-key: sk-ccr-anything' -d '{"model":"claude-sonnet-4-5-20250929","max_tokens":64,"messages":[{"role":"user","content":"sag hallo"}]}'`
Expected: Antwort kommt von qwen (lokal), kein Anthropic-Aufruf. ccr-Log (`docker logs claude-switcher-router-1`) zeigt Route auf `ollama`. Falls der `openai`-Transformer mit Ollama hakt (Tool-Use/Streaming) → hier dokumentieren und ggf. Transformer anpassen.

- [ ] **Step 5: Fail-closed gegenprüfen**

Run: `docker stop claude-switcher-ollama-1` und Schritt 4 wiederholen.
Expected: Anfrage scheitert (Ollama weg) — **kein** automatischer Cloud-Ausweich, kein Anthropic/Gemini-Traffic. Danach `docker start claude-switcher-ollama-1`.

- [ ] **Step 6: Zurück auf cloud (Aufräumen)**

Run: `curl -sS -X POST http://localhost:2000/api/mode -H 'Content-Type: application/json' -d '{"pool":"cloud","supermodel":true}'`
Expected: Router-Config wieder gemini/openrouter; Session-Pin = `orchestrator-cloud`-Top.

---

### Task 10: Verifikations-Agent `orchestrator-check` (Konsistenz beim UI-Umschalten sichtbar machen)

**Files:**
- Create: `agents/orchestrator-check.md` (Repo-Vorlage)
- Deploy: nach `~/.claude/agents/orchestrator-check.md` kopieren (maschinen-spezifisch, nicht im Container)

**Interfaces:**
- Consumes: `GET /api/whoami` (nach Task 7 ehrlich, inkl. Ollama), `GET /api/supermodel` (Pool), `GET /api/ai-models` (gefilterte Matrix → `orchestrator-{pool}`-Top), `~/.claude/settings.json` (env). Optional `ollama ps`.
- Produces: ein Markdown-Agent, der **nur liest/prüft** und einen Konsistenz-Verdikt ausgibt. Er vergleicht das real laufende Session-Modell (whoami) mit dem `orchestrator-{pool}`-Top und belegt bei local zusätzlich die fail-closed-Route (ccr→Ollama, kein Cloud, Opus weg).

**Idee/Workflow:** Du togglest den Pool **selbst in der UI** (das löst ggf. den Wrapper-Restart aus). Danach — in der frischen Session — rufst du den Agent auf; er druckt eine Verdikt-Zeile (`✅ konsistent` / `❌ MISMATCH`). So wird über alle Pools sichtbar, dass die Session wirklich dem Orchestrator folgt (bei local: qwen statt heimlich Opus).

**Sicherheit (hart im Agent verankert):** Der Agent ruft **NIE** `/api/switch` oder `/api/mode` auf und schreibt **NIE** den Restart-Marker — sonst würde er die laufende Session mitten im Check killen. Er ist read-only.

- [ ] **Step 1: Agent-Datei schreiben**

`agents/orchestrator-check.md` mit folgendem Inhalt anlegen:

````markdown
---
name: orchestrator-check
description: Read-only Konsistenz-Prüfer für den Supermodell-Orchestrator. Verifiziert, dass das real laufende Session-Modell exakt dem orchestrator-{pool}-Top entspricht — nach jedem UI-Pool-Wechsel aufrufbar. Bei local belegt er zusätzlich die fail-closed-Route (ccr→Ollama, kein Cloud, Opus verschwunden). Schaltet NICHTS um (kein /api/switch, kein /api/mode), reine Beobachtung.
tools: Bash, Read
model: haiku
---

> **Repo-Kanonik.** Diese Datei ist die Vorlage. Auf jedem Rechner nach
> `~/.claude/agents/orchestrator-check.md` kopieren (`~/.claude` ist maschinen-spezifisch).

Du bist der **Orchestrator-Konsistenz-Prüfer**. Deine einzige Aufgabe: belegen, dass die
laufende Session genau das Modell ist, das als `orchestrator-{pool}` eingestellt ist —
für jeden Pool. Du **schaltest nichts um** und schreibst nichts. Read-only.

## Schritt 1 — Ist-Zustand lesen

```bash
WHO=$(curl -sS --max-time 5 http://localhost:2000/api/whoami)
POOL=$(curl -sS --max-time 5 http://localhost:2000/api/supermodel \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('pool','?'))" 2>/dev/null || echo '?')
echo "whoami: $WHO"
echo "pool:   $POOL"
```

## Schritt 2 — erwartetes Orchestrator-Top bestimmen

```bash
curl -sS --max-time 5 http://localhost:2000/api/ai-models \
  | python3 -c '
import sys,json
ms=json.load(sys.stdin)
cat="orchestrator-"+"'"$POOL"'"
cs=[m for m in ms if m.get("category")==cat and m.get("enabled") and not m.get("autoDisabled")]
cs.sort(key=lambda m: m.get("orderIdx") if m.get("orderIdx") is not None else 1e9)
print(cs[0]["modelId"] if cs else "NONE")'
```
Das ausgegebene Modell ist das **erwartete** Session-Modell (`EXPECT`).

## Schritt 3 — Verdikt

Vergleiche `EXPECT` mit dem Modell-Teil aus `whoami`:
- **Match** → `✅ konsistent: Pool=$POOL, Session=$EXPECT (whoami bestätigt).`
- **Mismatch** → `❌ MISMATCH: Pool=$POOL, erwartet=$EXPECT, aber whoami=$WHO.`
- `EXPECT=NONE` bei local → `⏸ local pending: kein aktiviertes lokales Orchestrator-Modell (fail-closed, kein Cloud-Ausweich) — whoami=$WHO.`

## Schritt 4 — nur bei `pool=local`: fail-closed-Beleg

```bash
# (a) Session geht über den Router (nicht Anthropic direkt):
python3 -c "import json;print(json.load(open('$HOME/.claude/settings.json')).get('env',{}).get('ANTHROPIC_BASE_URL',''))"
# (b) Ollama lädt das Modell wirklich (Opus ist NICHT im Spiel):
ollama ps 2>/dev/null || echo 'ollama ps nicht verfügbar'
```
Erwartung: (a) endet auf `:3456` (ccr), (b) listet das lokale Modell. whoami enthält
`via Ollama (lokal)` und **kein** „Anthropic". Wenn whoami „Anthropic"/Opus zeigt, obwohl
Pool=local → **❌ Konsistenz verletzt** (Opus läuft heimlich) klar melden.

## Regeln
- **Read-only:** NIE `/api/switch`, NIE `/api/mode`, NIE Restart-Marker schreiben — das würde
  die laufende Session killen. Nur `GET`-Endpoints + lokale Reads.
- Antworte mit **genau einer** Verdikt-Zeile (plus bei local die 2 Belegzeilen). Kompakt.
- Erreichst du den Switcher nicht (`localhost:2000`), melde ehrlich
  `Switcher nicht erreichbar — Konsistenz nicht prüfbar`.
````

- [ ] **Step 2: Agent auf die Maschine deployen**

```bash
cp /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher/agents/orchestrator-check.md /home/dmammadov/.claude/agents/orchestrator-check.md
```

- [ ] **Step 3: Smoke-Test (Switcher muss laufen)**

Den Agent aufrufen (im aktuellen Pool) und prüfen, dass er eine Verdikt-Zeile liefert
(`✅`/`❌`/`⏸`) und **nichts** umschaltet. Endpoints einzeln gegenchecken:
```bash
curl -sS http://localhost:2000/api/whoami; echo
curl -sS http://localhost:2000/api/supermodel; echo
```
Expected: whoami-Zeile + Pool; Agent-Verdikt passt zum aktuellen Pool.

- [ ] **Step 4: Beobachtungslauf (manuell, der eigentliche Zweck)**

Pro Pool: in der UI (`http://localhost:2000`) umschalten → 3-5 s Restart abwarten → in der
frischen Session den Agent aufrufen → Verdikt lesen.
Expected:
- cloud → `✅ konsistent: Pool=cloud, Session=<orchestrator-cloud-Top>`
- free  → `✅ konsistent: Pool=free, Session=<orchestrator-free-Top>`
- local → `✅ konsistent: Pool=local, Session=qwen2.5-coder:7b (via Ollama)` + BASE_URL `:3456` + `ollama ps` zeigt das Modell. **Kein** „Anthropic"/Opus.

- [ ] **Step 5: Commit (Repo-Vorlage)**

```bash
cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher
git add agents/orchestrator-check.md
git commit -m "feat(agents): add read-only orchestrator-check consistency verifier"
```

(Die Kopie unter `~/.claude/agents/` ist maschinen-spezifisch — nicht committen.)

---

### Task 11: Doku nachziehen (Phase E erledigt)

**Files:**
- Modify: `SUPERMODELL.md`
- Modify: `/home/dmammadov/Dokumente/brain/02 Projekte/Claude Code Switcher.md`
- Modify: `docs/superpowers/specs/2026-06-23-orchestrator-session-consistency-design.md` (Status)

**Interfaces:** keine (Dokumentation).

- [ ] **Step 1: SUPERMODELL.md aktualisieren**

Run: `grep -n "Phase E\|Phase-E\|kein ccr-Routing\|local.*Opus\|orchestrator.*local" SUPERMODELL.md`
Jede Stelle, die „Phase E (noch nicht)", „Session bleibt Opus" oder „kein lokales Routing" sagt, umschreiben auf: **local routet die Session echt über ccr→Ollama auf das `orchestrator-local`-Top; Opus verschwindet; Router-Config local = nur Ollama (fail-closed).** Den Konsistenz-Satz ergänzen: „Session-Modell == `orchestrator-{pool}`-Top, für alle Pools."

- [ ] **Step 2: brain-Note aktualisieren**

In `/home/dmammadov/Dokumente/brain/02 Projekte/Claude Code Switcher.md` im Abschnitt „Supermodell-Modus" einen Satz ergänzen: bei local läuft die Session real über ccr→Ollama (Opus weg), Router-Config local = ausschließlich Ollama (fail-closed). Falls eine „Phase E offen"-Notiz existiert → auf erledigt setzen.

- [ ] **Step 3: Spec-Status auf umgesetzt setzen**

In `docs/superpowers/specs/2026-06-23-orchestrator-session-consistency-design.md` Zeile 5 (`- **Status:** ...`) ändern zu: `- **Status:** Umgesetzt (Teil 1 + Teil 2 / Phase E) — siehe Plan 2026-06-23-orchestrator-session-consistency.md`.

- [ ] **Step 4: Commit**

```bash
cd /home/dmammadov/Dokumente/KI-Projekte/claude-code-switcher
git add SUPERMODELL.md docs/superpowers/specs/2026-06-23-orchestrator-session-consistency-design.md
git commit -m "docs: orchestrator-session consistency + Phase E implemented"
```

(Die brain-Note liegt außerhalb des Repos — separat speichern, nicht committen.)

---

## Self-Review

**Spec coverage (gegen `2026-06-23-orchestrator-session-consistency-design.md`):**
- Teil 1 cloud/free Pin = Cascade-Top → Tasks 1, 2. ✓ (anthropic-direkt mit gesetztem `cfg.model`; google/openrouter via Router).
- Teil 2 (1) Ollama als Router-Provider → Task 3. ✓
- Teil 2 (2) local-Pin verdrahten + Restart → Tasks 5, 6. ✓
- Teil 2 (3) fail-closed nur Ollama, mode=manual → Task 4 (config) + Task 5 (kein Reroute ohne Modell); `mode=manual` bleibt in `setMode` unverändert. ✓
- Teil 2 (4) `localOrchestratorPending` bleibt Signal, kein Cloud-Ausweich → Task 5 `localTop==null`-Zweig. ✓
- `whoami` ehrlich für local (sonst Platzhalter-Modell statt qwen) → Task 7. ✓
- Verifikations-Agent (User-Wunsch: Konsistenz beim UI-Umschalten sichtbar) → Task 10. ✓
- Wiring: Router→Ollama-Erreichbarkeit → Task 8; ccr-Transformer/Tool-Use-Verifikation → Task 9 Step 4; Restart-Pfad → Task 6. ✓
- Betroffene Dateien/Tests/Doku aus dem Spec → alle abgedeckt (Tasks 2-11). ✓

**Placeholder-Scan:** Kein TBD/„handle edge cases"; jeder Code-Step zeigt vollständigen Code, jeder Run-Step ein exaktes Kommando mit erwartetem Ergebnis. ✓

**Typ-Konsistenz:** `orchestratorTopModel` (Task 1) wird in Task 2 (cloud/free) und Task 5 (local) verwendet; `pinViaRouter`/`pinAnthropicDirect`/`envOf` (Task 2) in Task 5 wiederverwendet; `buildProvidersForPool`/`buildOllamaProvider`/`ollamaBaseUrl` (Task 3) in Task 4 verwendet; `activeRoute`-Vertrag (von `pinViaRouter` gesetzt: `{provider, model}`) in Task 6 (`setMode`), Task 4 (`writeRouterConfig`) und Task 7 (`whoami`) gelesen. Signaturen konsistent. ✓

**Hinweis (außerhalb Scope):** `AutoPromoteService` holt nach Cooldown das Top zurück. Wenn das Top über den Router läuft (google/openrouter), sollte AutoPromote analog `restartRouter()` triggern — prüfen, ob das bereits geschieht; falls nicht, separater Folge-Task (nicht Teil dieses Plans).

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-23-orchestrator-session-consistency.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — Ich dispatch pro Task einen frischen Subagent, Review zwischen den Tasks, schnelle Iteration.

**2. Inline Execution** — Tasks in dieser Session ausführen (executing-plans), Batch mit Checkpoints zum Review.

**Welcher Ansatz?**
