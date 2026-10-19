package com.example.shop.chaos;

import java.util.Arrays;
import java.util.Optional;

/**
 * Failure scenarios that can be switched on at runtime.
 * Intensity is always 1..100; what it means is scenario-specific (see {@link #intensityHint}).
 */
public enum Scenario {

    GC_PRESSURE("gc-pressure", "GCしすぎ",
            "GET /api/products が大量の一時オブジェクトを確保し、しばらく保持する",
            "1リクエストあたり確保量 (intensity × 0.5MB)", 50),
    CPU_HOTSPOT("cpu-hotspot", "特定コードが重い",
            "GET /api/products/{id} の PriceCalculator が非効率な価格トレンド計算を行う",
            "計算量 (O(n²), n = 1,000 + intensity × 40)", 50),
    SLOW_QUERY("slow-query", "クエリが遅い (N+1 + インデックスなし)",
            "GET /api/search が検索結果ごとにインデックスのない order_items を集計する",
            "遅いパスを通るリクエストの割合 (%)", 100),
    POOL_EXHAUSTION("pool-exhaustion", "DBコネクションプール枯渇",
            "POST /api/orders がトランザクション内で重い集計クエリ + 帳票生成を行い、接続を握り続ける",
            "接続保持時間 (intensity × 50ms) + 重い集計クエリ", 80),
    EXTERNAL_LATENCY("external-latency", "外部API遅延 + リトライストーム",
            "決済API (payment-gateway) が遅延し、タイムアウト → 即時リトライを繰り返す",
            "決済APIの遅延 (intensity × 30ms ±40%、タイムアウト1秒) / 一部503。60以上でほぼ全滅", 30),
    LOCK_CONTENTION("lock-contention", "ロック競合",
            "POST /api/orders の在庫引当がグローバルロック内で遅い同期処理を行う",
            "ロック保持時間 (intensity × 4ms)", 50),
    MEMORY_LEAK("memory-leak", "メモリリーク → OOM",
            "GET /api/products/{id} と /api/recommendations が消えないキャッシュに溜め込む",
            "1リクエストあたりのリーク量 (intensity × 2KB)", 20),
    LOG_NOISE("log-noise", "変なログ / エラー",
            "旧形式クーポン付きの注文で例外スタックトレースを出して500、全体でも無意味なWARNを連発",
            "WARNの量 / 旧形式クーポンでの失敗率", 50),
    NOISY_NEIGHBOR("noisy-neighbor", "ノイジーネイバー",
            "同じCPUコアに固定された別プロセス (noisy-batch) がCPUを食い潰す",
            "CPUを占有する時間の割合 (%、最大90)。高いCPU優先度で動くので85前後で全APIが数倍遅くなる", 85);

    private final String id;
    private final String title;
    private final String description;
    private final String intensityHint;
    private final int defaultIntensity;

    Scenario(String id, String title, String description, String intensityHint, int defaultIntensity) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.intensityHint = intensityHint;
        this.defaultIntensity = defaultIntensity;
    }

    public String id() { return id; }
    public String title() { return title; }
    public String description() { return description; }
    public String intensityHint() { return intensityHint; }
    public int defaultIntensity() { return defaultIntensity; }

    public static Optional<Scenario> fromId(String id) {
        return Arrays.stream(values()).filter(s -> s.id.equals(id)).findFirst();
    }
}
