/* File: Main.java
 * Single-file Binance Futures 1m Kline collector + backfill + HTF aggregation + indicators + trend + TG notify.
 * EMA periods: 21/55/96/200. Now supports proxy via env: HTTP_PROXY / HTTPS_PROXY / PROXY_HOST / PROXY_PORT.
 * JDK >= 21, no third-party libs. Fit for Termux (javac Main.java && java Main).
 *
 * Quick proxy env examples (Termux):
 *   export HTTP_PROXY=http://127.0.0.1:7890
 *   export HTTPS_PROXY=http://127.0.0.1:7890
 *   # or
 *   export PROXY_HOST=127.0.0.1
 *   export PROXY_PORT=7890
 */

import java.net.*;
import java.net.http.*;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {

    // ================== SYMBOL & ENDPOINT ==================
    static final String SYMBOL_STREAM = "btcusdc";       // for WS stream (lowercase)
    static final String SYMBOL_REST   = "BTCUSDC";       // for REST
    static final String REST_BASE     = "https://fapi.binance.com"; // USDⓈ-M Futures REST
    static final String WS_URL        = "wss://fstream.binance.com/stream?streams=" + SYMBOL_STREAM + "@kline_1m";

    // ================== STORAGE ============================
    static final Path DATA_DIR    = Paths.get("data");
    static final Path ONE_MIN_CSV = DATA_DIR.resolve("btcusdc_1m.csv");
    static final int  ONE_MIN_KEEP = 4000;    // 保留的 1m 根数
    static final int  HTF_KEEP     = 1000;    // 高周期保留

    // ================== API 配置区（支持环境变量覆盖） ==================
    // 环境变量名：MELODY_BINANCE_KEY / MELODY_BINANCE_SECRET
    static final String API_KEY    = getenvOrDefault("MELODY_BINANCE_KEY",
            "L19NNPSF4PaokqKgLRpyjjDR2FBvIPPnji8idHkTP13IGUu4dsmPnF8BuYe5aKmZ");
    static final String SECRET_KEY = getenvOrDefault("MELODY_BINANCE_SECRET",
            "nM8GmY05ahWwRV8vYFfDUCDqdXQtn0uxmejNlkFCVAd7M05utuvFVp4bwOPoKmod");

    // ================== Telegram ===========================
    static final String TG_TOKEN  = "8008724459:AAFN9y8wRPXqfYPmhOvP8rYHIm53KtOkwjI";
    static final String TG_CHATID = "5364167213";

    // ================== Indicator params ===================
    static final int EMA_21 = 21, EMA_55 = 55, EMA_96 = 96, EMA_200 = 200;
    static final int MACD_FAST = 12, MACD_SLOW = 26, MACD_SIGNAL = 9;
    static final int RSI_LEN = 14;
    static final int BOLL_LEN = 20;
    static final int STOCHRSI_LEN = 14;

    // 入场/止盈演示参数
    static final double ENTRY_OFFSET_PCT = 0.0008; // 0.08%
    static final double TP_OFFSET_PCT    = 0.0020; // 0.20%

    // ================== Rate Limit (REST) ==================
    static final int    RL_CAPACITY = 2400;
    static final long   RL_REFILL_MS = 60_000L;
    static int          rlTokens = RL_CAPACITY;
    static long         rlLastRefill = System.currentTimeMillis();

    // ================== In-memory series ===================
    static final Deque<Candle> oneMin = new ArrayDeque<>();
    static final Map<TF, Deque<Candle>> tfMap = new EnumMap<>(TF.class);
    static {
        for (TF tf : TF.values()) if (tf != TF.M1) tfMap.put(tf, new ArrayDeque<>());
    }

    static final AtomicBoolean running = new AtomicBoolean(true);

    // Shared HttpClient (respects proxy env)
    static final HttpClient HTTP = buildHttpClient();

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DATA_DIR);
        if (!Files.exists(ONE_MIN_CSV)) {
            Files.writeString(ONE_MIN_CSV, "openTime,open,high,low,close,volume,closeTime\n", StandardCharsets.UTF_8);
        }

        // 1) 回补 1m 历史
        int need = Math.max(ONE_MIN_KEEP, 3000);
        backfillOneMinute(need);

        // 2) 建立 WS 订阅（通过同一 HttpClient 以继承代理配置）
        System.out.println(ts() + " connecting WS: " + WS_URL);
        HTTP.newWebSocketBuilder()
                .buildAsync(URI.create(WS_URL), new WsListener())
                .join();

        while (running.get()) Thread.sleep(1000);
    }

    // Build HttpClient with proxy from env
    static HttpClient buildHttpClient() {
        ProxySelector selector = null;
        // Priority: HTTP(S)_PROXY -> PROXY_HOST/PROXY_PORT
        String httpProxy = firstNonEmpty(System.getenv("HTTPS_PROXY"), System.getenv("https_proxy"),
                                         System.getenv("HTTP_PROXY"), System.getenv("http_proxy"));
        if (httpProxy != null) {
            try {
                URI u = URI.create(httpProxy);
                String host = u.getHost();
                int port = (u.getPort() > 0) ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
                if (host != null) selector = ProxySelector.of(new InetSocketAddress(host, port));
                System.out.println(ts() + " using proxy: " + host + ":" + port);
            } catch (Exception ignore) {}
        }
        if (selector == null) {
            String host = getenvOrDefault("PROXY_HOST", "");
            String port = getenvOrDefault("PROXY_PORT", "");
            if (!host.isEmpty() && !port.isEmpty()) {
                try {
                    selector = ProxySelector.of(new InetSocketAddress(host, Integer.parseInt(port)));
                    System.out.println(ts() + " using proxy: " + host + ":" + port);
                } catch (Exception ignore) {}
            }
        }
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (selector != null) b.proxy(selector);
        return b.build();
    }

    static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return null;
    }

    // ================== Backfill via REST ==================
    static void backfillOneMinute(int target) {
        try {
            restoreOneMinFromCsvTail(ONE_MIN_CSV, target);
            int have = oneMin.size();
            if (have >= target) {
                System.out.println(ts() + " backfill skipped, already have " + have);
                aggregateAllFromOneMin();
                return;
            }

            System.out.println(ts() + " backfill need: " + (target - have));
            long endTime = System.currentTimeMillis(); // 毫秒

            while (oneMin.size() < target) {
                int limit = 1000;
                String url = REST_BASE + "/fapi/v1/klines?symbol=" + SYMBOL_REST + "&interval=1m&limit=" + limit + "&endTime=" + endTime;
                restRateLimitGate();
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("X-MBX-APIKEY", API_KEY)
                        .GET().build();
                HttpResponse<String> resp;
                try {
                    resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                } catch (java.net.ConnectException | java.nio.channels.UnresolvedAddressException e) {
                    System.err.println(ts() + " backfill connect/DNS error. Check proxy/DNS. " + e);
                    // 不中断：直接跳出回补，转入 WS，后续靠 WS 慢慢积累
                    break;
                }
                if (resp.statusCode() != 200) {
                    System.err.println(ts() + " backfill HTTP " + resp.statusCode() + " body: " + resp.body());
                    Thread.sleep(500);
                    continue;
                }
                List<Candle> batch = parseRestKlines(resp.body());
                if (batch.isEmpty()) break;

                for (Candle c : batch) {
                    if (!oneMin.isEmpty() && oneMin.getLast().closeTime >= c.closeTime) continue;
                    oneMin.addLast(c);
                    appendCsv(ONE_MIN_CSV, c);
                }
                trimDeque(oneMin, ONE_MIN_KEEP);

                endTime = batch.get(0).openTime - 1;
                System.out.println(ts() + " backfill progress: " + oneMin.size());

                if (batch.size() < limit) break;
            }

            aggregateAllFromOneMin();
            System.out.println(ts() + " backfill done. 1m size=" + oneMin.size());
        } catch (Exception e) {
            System.err.println(ts() + " backfill error: " + e);
        }
    }

    static void restRateLimitGate() throws InterruptedException {
        long now = System.currentTimeMillis();
        long elapsed = now - rlLastRefill;
        if (elapsed >= RL_REFILL_MS) {
            rlTokens = RL_CAPACITY;
            rlLastRefill = now;
        }
        while (rlTokens <= 0) {
            Thread.sleep(50);
            now = System.currentTimeMillis();
            elapsed = now - rlLastRefill;
            if (elapsed >= RL_REFILL_MS) {
                rlTokens = RL_CAPACITY;
                rlLastRefill = now;
            }
        }
        rlTokens--;
    }

    static List<Candle> parseRestKlines(String json) {
        List<Candle> out = new ArrayList<>();
        if (json == null || json.length() < 10) return out;

        String s = json.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return out;

        String[] rows = s.split("\\],\\[");
        for (String row : rows) {
            String r = row.replace("[", "").replace("]", "");
            String[] a = r.split(",");
            if (a.length < 7) continue;
            try {
                long t = Long.parseLong(a[0].trim());
                double o = parseNum(a[1]);
                double h = parseNum(a[2]);
                double l = parseNum(a[3]);
                double c = parseNum(a[4]);
                double v = parseNum(a[5]);
                long T = Long.parseLong(a[6].trim());
                Candle cd = new Candle(t, o, h, l, c, v, T);
                out.add(cd);
            } catch (Exception ignore) { }
        }
        return out;
    }

    static double parseNum(String s) {
        s = s.trim();
        if (s.startsWith("\"")) s = s.substring(1);
        if (s.endsWith("\"")) s = s.substring(0, s.length() - 1);
        return Double.parseDouble(s);
    }

    // ================== WebSocket ==========================
    static class WsListener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override public void onOpen(WebSocket ws) {
            System.out.println(ts() + " WS opened.");
            WebSocket.Listener.super.onOpen(ws);
        }

        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                handleWsMessage(msg);
            }
            ws.request(1);
            return null;
        }

        @Override public void onError(WebSocket ws, Throwable error) {
            System.err.println(ts() + " WS error: " + error);
        }

        @Override public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            System.out.println(ts() + " WS closed: " + statusCode + " " + reason);
            running.set(false);
            return WebSocket.Listener.super.onClose(ws, statusCode, reason);
        }
    }

    static void handleWsMessage(String json) {
        KlineEvent ev = parseWsKline(json);
        if (ev == null) return;
        if (!ev.isClosed) return;

        Candle c = new Candle(ev.openTime, ev.open, ev.high, ev.low, ev.close, ev.volume, ev.closeTime);
        if (!oneMin.isEmpty() && oneMin.getLast().closeTime == c.closeTime) {
            oneMin.removeLast();
        }
        oneMin.addLast(c);
        trimDeque(oneMin, ONE_MIN_KEEP);
        appendCsv(ONE_MIN_CSV, c);

        aggregateAllFromOneMin();
        AnalysisResult ar = analyzeAndDecide();
        if (ar != null && ar.shouldNotify) {
            tgSend(buildTelegramText(ar));
            System.out.println(ts() + " TG notified. trend=" + ar.trend + " score=" + ar.score);
        }
    }

    static KlineEvent parseWsKline(String json) {
        if (json == null) return null;
        int kPos = json.indexOf("\"k\":");
        if (kPos < 0) return null;
        int start = json.indexOf('{', kPos);
        if (start < 0) return null;
        int brace = 1, i = start + 1;
        while (i < json.length() && brace > 0) {
            char ch = json.charAt(i++);
            if (ch == '{') brace++;
            else if (ch == '}') brace--;
        }
        if (brace != 0) return null;
        String k = json.substring(start, i);

        Long t = getLong(k, "\"t\":");
        Long T = getLong(k, "\"T\":");
        Boolean x = getBool(k, "\"x\":");
        Double o = getStrNum(k, "\"o\":\"");
        Double h = getStrNum(k, "\"h\":\"");
        Double l = getStrNum(k, "\"l\":\"");
        Double c = getStrNum(k, "\"c\":\"");
        Double v = getStrNum(k, "\"v\":\"");
        if (t == null || T == null || x == null || o == null || h == null || l == null || c == null || v == null) return null;

        KlineEvent ev = new KlineEvent();
        ev.openTime = t; ev.closeTime = T; ev.isClosed = x;
        ev.open = o; ev.high = h; ev.low = l; ev.close = c; ev.volume = v;
        return ev;
    }

    static Long getLong(String s, String key) {
        int p = s.indexOf(key); if (p < 0) return null; p += key.length();
        int q = p; while (q < s.length() && Character.isDigit(s.charAt(q))) q++;
        try { return Long.parseLong(s.substring(p, q)); } catch (Exception e) { return null; }
    }
    static Boolean getBool(String s, String key) {
        int p = s.indexOf(key); if (p < 0) return null; p += key.length();
        if (s.startsWith("true", p)) return true; if (s.startsWith("false", p)) return false; return null;
    }
    static Double getStrNum(String s, String key) {
        int p = s.indexOf(key); if (p < 0) return null; p += key.length();
        int q = s.indexOf('"', p); if (q < 0) return null;
        try { return Double.parseDouble(s.substring(p, q)); } catch (Exception e) { return null; }
    }

    // ================== CSV ================================
    static void appendCsv(Path csv, Candle ev) {
        String line = ev.openTime + "," + ev.open + "," + ev.high + "," + ev.low + "," + ev.close + "," + ev.volume + "," + ev.closeTime + "\n";
        try {
            Files.writeString(csv, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println(ts() + " write CSV fail: " + e.getMessage());
        }
    }
    static void restoreOneMinFromCsvTail(Path csv, int max) {
        try {
            if (!Files.exists(csv)) return;
            List<String> all = Files.readAllLines(csv, StandardCharsets.UTF_8);
            for (int i = Math.max(1, all.size() - max); i < all.size(); i++) {
                String[] a = all.get(i).split(",");
                if (a.length < 7) continue;
                Candle c = new Candle(Long.parseLong(a[0]),
                        Double.parseDouble(a[1]),
                        Double.parseDouble(a[2]),
                        Double.parseDouble(a[3]),
                        Double.parseDouble(a[4]),
                        Double.parseDouble(a[5]),
                        Long.parseLong(a[6]));
                oneMin.addLast(c);
            }
            trimDeque(oneMin, ONE_MIN_KEEP);
            System.out.println(ts() + " restored from CSV: " + oneMin.size());
        } catch (Exception e) {
            System.err.println(ts() + " restore fail: " + e.getMessage());
        }
    }

    // ================== Aggregation ========================
    enum TF { M1(60), M15(900), M30(1800), H1(3600), H2(7200), H4(14400);
        final int seconds; TF(int s){this.seconds=s;} }

    static void aggregateAllFromOneMin() {
        if (oneMin.isEmpty()) return;
        for (TF tf : List.of(TF.M15, TF.M30, TF.H1, TF.H2, TF.H4)) {
            int bucket = tf.seconds / 60;
            if (oneMin.size() < bucket) continue;
            List<Candle> src = new ArrayList<>(bucket);
            Iterator<Candle> it = oneMin.descendingIterator();
            for (int i=0;i<bucket && it.hasNext();i++) src.add(it.next());
            Collections.reverse(src);
            Candle agg = mergeCandles(src);
            Deque<Candle> dq = tfMap.get(tf);
            if (dq.isEmpty() || dq.getLast().closeTime < agg.closeTime) dq.addLast(agg);
            else { dq.removeLast(); dq.addLast(agg); }
            trimDeque(dq, HTF_KEEP);
        }
    }
    static Candle mergeCandles(List<Candle> list) {
        double open = list.get(0).open;
        double close = list.get(list.size()-1).close;
        double high = list.stream().mapToDouble(c->c.high).max().orElse(open);
        double low  = list.stream().mapToDouble(c->c.low).min().orElse(open);
        double vol  = list.stream().mapToDouble(c->c.volume).sum();
        long   ot   = list.get(0).openTime;
        long   ct   = list.get(list.size()-1).closeTime;
        return new Candle(ot, open, high, low, close, vol, ct);
    }
    static void trimDeque(Deque<?> dq, int keep){ while(dq.size()>keep) dq.removeFirst(); }

    // ================== Indicators & Decision ==============
    static AnalysisResult analyzeAndDecide() {
        if (oneMin.size() < 220) return null;
        for (TF tf : List.of(TF.M15, TF.M30, TF.H1, TF.H2, TF.H4)) {
            Deque<Candle> dq = tfMap.get(tf);
            if (dq == null || dq.size() < Math.max(EMA_200 + 5, 60)) return null;
        }

        Map<TF,double[]> closes = new EnumMap<>(TF.class);
        for (TF tf : List.of(TF.M15, TF.M30, TF.H1, TF.H2, TF.H4)) closes.put(tf, dequeCloses(tfMap.get(tf)));
        double[] m1Closes = dequeCloses(oneMin);
        double[] m5Closes = buildSyntheticCloses(oneMin, 5);

        Map<TF,Indicators> ind = new EnumMap<>(TF.class);
        for (TF tf : closes.keySet()) {
            double[] c = closes.get(tf);
            Indicators x = new Indicators();
            x.ema21  = ema(c, EMA_21);
            x.ema55  = ema(c, EMA_55);
            x.ema96  = ema(c, EMA_96);
            x.ema200 = ema(c, EMA_200);
            x.macd   = macdLast(c, MACD_FAST, MACD_SLOW, MACD_SIGNAL);
            x.rsi    = rsiLast(c, RSI_LEN);
            x.boll   = bollLast(c, BOLL_LEN, 2.0);
            x.stochRsi = stochRsiLast(c, STOCHRSI_LEN);
            x.volAvg = volAvg(tfMap.get(tf), 20);
            ind.put(tf, x);
        }

        int score = 0;
        for (TF tf : List.of(TF.M15, TF.M30, TF.H1, TF.H2, TF.H4)) {
            Indicators x = ind.get(tf);
            double px = last(closes.get(tf));
            if (px > x.ema21 && x.ema21 > x.ema55 && x.ema55 > x.ema96 && x.ema96 > x.ema200) score += 3;
            else if (px > x.ema21 && px > x.ema55 && px > x.ema96 && px > x.ema200) score += 2;
            else if (px > x.ema55 && x.ema55 > x.ema96) score += 1;

            if (px < x.ema21 && x.ema21 < x.ema55 && x.ema55 < x.ema96 && x.ema96 < x.ema200) score -= 3;
            else if (px < x.ema21 && px < x.ema55 && px < x.ema96 && px < x.ema200) score -= 2;
            else if (px < x.ema55 && x.ema55 < x.ema96) score -= 1;

            score += (x.macd > 0) ? 1 : -1;
            if (x.rsi > 55) score += 1; else if (x.rsi < 45) score -= 1;
        }
        String trend = score>=6? "LONG" : score<=-6? "SHORT" : "NEUTRAL";

        double last1 = last(m1Closes), last5 = last(m5Closes);
        double ref = (last1 + last5)/2.0;
        double entry, tp;
        if ("LONG".equals(trend)) { entry = ref*(1-ENTRY_OFFSET_PCT); tp = ref*(1+TP_OFFSET_PCT); }
        else if ("SHORT".equals(trend)) { entry = ref*(1+ENTRY_OFFSET_PCT); tp = ref*(1-TP_OFFSET_PCT); }
        else { entry = ref; tp = ref; }

        AnalysisResult ar = new AnalysisResult();
        ar.trend = trend; ar.score = score;
        ar.px = r2(last1); ar.entry = r2(entry); ar.tp = r2(tp);
        ar.detail = ind; ar.shouldNotify = !"NEUTRAL".equals(trend);
        return ar;
    }

    static String buildTelegramText(AnalysisResult ar){
        StringBuilder sb = new StringBuilder();
        sb.append("BTCUSDC Trend: ").append(ar.trend).append(" (score ").append(ar.score).append(")\n")
          .append("Last: ").append(ar.px).append("\n")
          .append("Entry: ").append(ar.entry).append("  TP: ").append(ar.tp).append("\n\n");
        for (TF tf : List.of(TF.M15, TF.M30, TF.H1, TF.H2, TF.H4)) {
            Indicators x = ar.detail.get(tf);
            sb.append(tf.name())
              .append("  EMA21:").append(f2(x.ema21))
              .append("  EMA55:").append(f2(x.ema55))
              .append("  EMA96:").append(f2(x.ema96))
              .append("  EMA200:").append(f2(x.ema200))
              .append("  MACD:").append(f4(x.macd))
              .append("  RSI:").append(f2(x.rsi))
              .append("  BOLL.mid:").append(f2(x.boll.mid))
              .append("  vAvg20:").append(f2(x.volAvg))
              .append("\n");
        }
        sb.append("\nTime: ").append(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        return sb.toString();
    }

    static void tgSend(String text){
        try{
            String url = "https://api.telegram.org/bot"+TG_TOKEN+"/sendMessage";
            String body = "chat_id="+TG_CHATID+"&text="+java.net.URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(r->{ if(r.statusCode()>=300) System.err.println(ts()+" TG send fail: "+r.statusCode()+" "+r.body()); });
        }catch(Exception e){ System.err.println(ts()+" TG error: "+e.getMessage()); }
    }

    // ================== Utils ==============================
    static String ts(){ return "["+DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now())+"]"; }
    static String getenvOrDefault(String k, String def){ String v = System.getenv(k); return (v==null||v.isEmpty())? def : v; }

    static double[] dequeCloses(Deque<Candle> dq){ double[] a=new double[dq.size()]; int i=0; for(Candle c: dq) a[i++]=c.close; return a; }
    static double last(double[] a){ return a[a.length-1]; }
    static double r2(double v){ return Math.round(v*100.0)/100.0; }
    static String f2(double v){ return String.format(Locale.US,"%.2f",v); }
    static String f4(double v){ return String.format(Locale.US,"%.4f",v); }

    static double[] buildSyntheticCloses(Deque<Candle> dq, int m){
        int bucket=m, total=dq.size()/bucket; if(total<=0) return new double[]{ dq.getLast().close };
        double[] out=new double[total]; int idx=0, i=0;
        for(Candle c: dq){ if(i%bucket==(bucket-1)) out[idx++]=c.close; i++; }
        return Arrays.copyOf(out, idx);
    }

    // ================== Indicators =========================
    static double ema(double[] a, int n){
        if(a.length<n) return last(a);
        double k=2.0/(n+1.0), e=mean(Arrays.copyOfRange(a,0,n));
        for(int i=n;i<a.length;i++) e=a[i]*k + e*(1-k);
        return e;
    }
    static double mean(double[] a){ if(a.length==0) return 0; double s=0; for(double v: a) s+=v; return s/a.length; }
    static double stddev(double[] a){ if(a.length==0) return 0; double mu=mean(a), s2=0; for(double v:a){ double d=v-mu; s2+=d*d; } return Math.sqrt(s2/a.length); }

    static double rsiLast(double[] a, int n){
        if(a.length<n+1) return 50;
        double gain=0, loss=0;
        for(int i=a.length-n;i<a.length;i++){
            double d=a[i]-a[i-1];
            if(d>0) gain+=d; else loss-=d;
        }
        double rs = (loss==0)? 100 : (gain/Math.max(loss,1e-9));
        return 100 - (100/(1+rs));
    }
    static Boll bollLast(double[] a, int n, double k){
        Boll b = new Boll();
        if(a.length<n){ b.mid=last(a); b.upper=b.mid; b.lower=b.mid; return b; }
        double[] w=Arrays.copyOfRange(a,a.length-n,a.length);
        double mid=mean(w), sd=stddev(w);
        b.mid=mid; b.upper=mid+k*sd; b.lower=mid-k*sd; return b;
    }
    static double stochRsiLast(double[] a, int n){
        if(a.length<n+1) return 0.5;
        double[] rsiSeq=new double[a.length-1];
        for(int i=1;i<a.length;i++){
            double up=Math.max(0,a[i]-a[i-1]);
            double dn=Math.max(0,a[i-1]-a[i]);
            double rs=(dn==0)? 100 : (up/Math.max(dn, 1e-9));
            rsiSeq[i-1]=100 - (100/(1+rs));
        }
        if(rsiSeq.length<n) return 0.5;
        double[] w=Arrays.copyOfRange(rsiSeq, rsiSeq.length-n, rsiSeq.length);
        double min=Arrays.stream(w).min().orElse(50), max=Arrays.stream(w).max().orElse(50);
        if(Math.abs(max-min)<1e-9) return 0.5;
        return (rsiSeq[rsiSeq.length-1]-min)/(max-min);
    }
    static double macdLast(double[] a, int fast, int slow, int signal){
        if(a.length<slow+signal+5) return 0;
        double[] dif=new double[a.length];
        double ef=a[0], es=a[0], kf=2.0/(fast+1.0), ks=2.0/(slow+1.0);
        for(int i=1;i<a.length;i++){ ef=a[i]*kf + ef*(1-kf); es=a[i]*ks + es*(1-ks); dif[i]=ef-es; }
        double dea=dif[0], ks9=2.0/(signal+1.0);
        for(int i=1;i<dif.length;i++){ dea=dif[i]*ks9 + dea*(1-ks9); }
        return dif[dif.length-1]-dea; // 柱（不*2）
    }
    static double volAvg(Deque<Candle> dq, int n){
        if(dq.size()==0) return 0;
        int m=Math.min(n, dq.size()); double s=0; Iterator<Candle> it=dq.descendingIterator();
        for(int i=0;i<m && it.hasNext(); i++) s+=it.next().volume;
        return s/m;
    }

    // ================== Models ============================
    static class Candle {
        long openTime, closeTime;
        double open, high, low, close, volume;
        Candle(long ot,double o,double h,double l,double c,double v,long ct){
            openTime=ot; open=o; high=h; low=l; close=c; volume=v; closeTime=ct;
        }
    }
    static class KlineEvent {
        long openTime, closeTime; boolean isClosed;
        double open, high, low, close, volume;
    }
    static class Indicators {
        double ema21, ema55, ema96, ema200;
        double macd, rsi, stochRsi, volAvg; Boll boll;
    }
    static class Boll { double mid, upper, lower; }
    static class AnalysisResult {
        String trend; int score; double px, entry, tp; Map<TF,Indicators> detail; boolean shouldNotify;
    }
}
