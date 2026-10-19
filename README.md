# Splunk Observability デモ: Java ショップ (Spring Boot)

Splunk Observability Cloud の **APM サービスビュー**から、次の2点をデモするためのアプリです。

- **問題の発見**: エラー率、レイテンシー
- **原因の発見**: ホストやプロセスのリソース、GC、DBクエリ、関数（プロファイリング）、ログ

障害パターンは、画面か CLI からいつでも ON/OFF できます。

## 構成

```
                 ┌──────────────── Docker host (host.name = DEMO_HOST_NAME) ──────────────────┐
 [k6 loadgen] ─→ │ shop-app (Spring Boot モノリス + Splunk OTel Java agent) ←→ PostgreSQL       │
 chaosctl/ブラウザ│   ├ /api/...     業務API                    └─→ payment-gateway (外部API役)  │
      └────────→ │   └ /chaos/      障害コントロール画面 + API                                 │
                 │ noisy-neighbor  (app と同じ CPU コアに固定、ON で CPU を奪う)                  │
                 │ splunk-otel-collector (/hostfs と pid:host でホストの全プロセスを観測)          │
                 └───────┬───────────────────────────────────────┬──────────────────────────────┘
                         ↓ トレース / メトリクス / プロファイル        ↓ アプリログ (HEC)
             Splunk Observability Cloud                    Splunk Enterprise
                         └──────── Log Observer Connect ────────┘
```

| コンテナ | 役割 |
|---|---|
| `app` | Java 21 / Spring Boot 3.5 のショップ（モノリス）。Splunk OTel Java agent でトレース、JVMメトリクス、AlwaysOn Profiling（CPUとメモリ）、ログを送る |
| `postgres` | 商品5千件、顧客2万件、注文30万件、注文明細100万件。`orders.customer_id` と `order_items.product_id` には**わざとインデックスを張っていない**。Database Monitoring 用に `pg_stat_statements` と監視ユーザー `otel` を用意している |
| `payment-gateway` | 外部決済APIの代役。計装していないので、APMでは推論サービス `payment-gateway` として表示される |
| `noisy-neighbor` | CPUを食うバッチ（プロセス名は `noisy-batch`）。`app` と同じコアに、高いCPU優先度で載せている |
| `otel-collector` | Splunk Distribution of OTel Collector。`hostmetrics`（process 含む）、`docker_stats`、`postgresql` の各レシーバーでメトリクスを集め、ログは HEC に転送する |
| `loadgen` | k6。約17 req/s の定常トラフィックを流す（`LOAD_MULTIPLIER` で倍率を変更） |

## 前提

- Docker Engine 25 以上と Docker Compose v2。Linux ホストか Docker Desktop
- Splunk Observability Cloud: realm と **Ingest トークン**
- Splunk Enterprise: HEC の URL、HEC トークン、ログ用の index（例: `o11y_demo`）。HEC トークンがその index に書き込めること
- Log Observer Connect で、Observability Cloud から上記の Splunk Enterprise を参照できるよう設定済みであること
- （任意）Splunk Database Monitoring のライセンス。対応リージョンの realm であること。なくても他の機能は動く

## 起動

```bash
cp .env.example .env
vi .env          # SPLUNK_REALM / SPLUNK_ACCESS_TOKEN / SPLUNK_HEC_URL / SPLUNK_HEC_TOKEN / SPLUNK_HEC_INDEX など
docker compose up -d --build
```

- 初回は DB の初期データ投入に 30〜60 秒かかります
- コントロール画面: http://localhost:8080/chaos/
- APM 上の名前: サービスは `shop-app`、環境は `o11y-demo`、ホストは `o11y-demo-host`。いずれも `.env` で変更できます
- 停止は `docker compose down`。DB も作り直すなら `docker compose down -v`
- 以前のバージョンで作った DB ボリュームが残っていると、Database Monitoring 用の拡張と監視ユーザーが作られません。一度 `docker compose down -v` してから起動してください

### `.env` の主な項目

| 変数 | 説明 |
|---|---|
| `SPLUNK_REALM` / `SPLUNK_ACCESS_TOKEN` | Observability Cloud の realm と Ingest トークン |
| `SPLUNK_HEC_URL` / `SPLUNK_HEC_TOKEN` / `SPLUNK_HEC_INDEX` | Splunk Enterprise の HEC。URL は `https://host:8088` の形で、`/services/collector` は付けない |
| `SPLUNK_HEC_TLS_INSECURE` | HEC が自己署名証明書のときは `true` |
| `SERVICE_NAME` / `DEPLOYMENT_ENV` / `DEMO_HOST_NAME` | APM 上のサービス名、環境名、ホスト名 |
| `DEMO_CPUSET` | `app` と `noisy-neighbor` を載せる CPU コア（既定 `0`）。`0,1` のように複数指定もできる |
| `LOAD_MULTIPLIER` | 負荷の倍率 |

## 障害の切り替え

### 画面

http://localhost:8080/chaos/ のトグルで ON/OFF し、スライダーで強度（1〜100）を変えます。

### CLI

```bash
./chaosctl status                         # 一覧
./chaosctl on cpu-hotspot                 # 既定の強度で ON
./chaosctl on pool-exhaustion -i 100      # 強度を指定して ON
./chaosctl set gc-pressure 80             # 強度だけ変更
./chaosctl off slow-query
./chaosctl off all
CHAOS_URL=http://demo-vm:8080 ./chaosctl status   # 別ホストの app を操作
```

`jq` があれば表示が整形されます（なくても動きます）。

- `/chaos/**` へのリクエストは Collector で捨てているので、APM には出ません
- 障害の状態は app のメモリ上だけに持っています。app が再起動するとすべて OFF に戻ります（memory-leak で OOM になったときも同様）

## 障害パターン一覧

既定の強度、ローカル1コアの環境で計測した値です。正常時は各APIが 10ms 前後、注文 (`POST /api/orders`) が 80ms 前後です。

| ID | 何が起きるか | 症状（実測） | 原因の見つけ方 |
|---|---|---|---|
| `gc-pressure` | `GET /api/products` が大量の一時オブジェクトを作り、しばらく保持する（ヒープは 256MB） | 全APIが遅くなり、特に `/api/products` は 10ms から 300ms 超になる。app の CPU が上がる | Runtime metrics の GC 時間、GC 回数、ヒープ。メモリプロファイルで `CatalogSnapshotBuilder.build` |
| `cpu-hotspot` | `PriceCalculator.computeHistoricalPriceTrend` が O(n²) の計算をする | `GET /api/products/{id}` が 10ms から 250ms 前後になる。app の CPU が約 50% | CPU プロファイルの flame graph で `PriceCalculator.computeHistoricalPriceTrend` |
| `slow-query` | 検索が部分一致になり、ヒットした商品ごとにインデックスのない `order_items` を集計する（N+1） | `GET /api/search` が 10ms から約 2s になる。Postgres の CPU が急上昇 | Database Query Performance の `SELECT COALESCE(SUM(quantity), 0) FROM order_items WHERE product_id = ?`（実行回数と合計時間が突出）。トレースに同じ SQL が 20 回並ぶ |
| `pool-exhaustion` | 注文処理がトランザクション内で重い順位集計クエリと帳票生成（約4s）を行い、接続を握り続ける。プールは 10 本 | 注文が約 6s。**関係のない API まで** 10ms から 0.6〜1s になり、一部が 503 | HikariCP の接続プールメトリクス（`db.client.connections.*`: 使用中が上限、待ちが発生）。トレースでは SQL の前に空白の待ち時間がある。DBQP には順位集計クエリが出る。エラーは `Connection is not available, request timed out` |
| `external-latency` | 決済APIが遅くなる。クライアントはタイムアウト 1s、バックオフなしで最大 4 回リトライする | 注文が約 1s。強度を 60 以上にすると、ほぼ全件が 4s で 502 | Service Map の `payment-gateway` エッジ。トレースに POST が複数回並ぶ。ログに `Payment attempt n/4 failed` |
| `lock-contention` | 在庫引当がグローバルロック内で遅い同期処理をする | 注文が 80ms から 400ms 前後になる。**CPU は低いまま** | flame graph（スレッドダンプ）で `InventoryService.reserve` / `syncWithLegacyWarehouse` に BLOCKED のスレッドが集中 |
| `memory-leak` | リクエストごとに、消えないキャッシュ（`PersonalizationCache`）に溜め込む | ヒープが右肩上がりになり、GC が増え、最後は OOM で落ちて再起動する（強度100で約90秒、既定の20で数分〜10分） | Runtime metrics のヒープ（ノコギリ波の底が上がり続ける）。メモリプロファイルで `PersonalizationCache.remember`。コンテナの再起動 |
| `log-noise` | 全APIで意味のない WARN を大量に出す。旧形式クーポン（`LEGACY:`）付きの注文は、スタックトレース付き ERROR で 500 を返す | 注文のエラー率が上がる。WARN は毎秒 100 行超 | Tag Spotlight で `coupon.type=legacy` にエラーが集中。関連ログに `Failed to resolve legacy coupon` |
| `noisy-neighbor` | 同じコアで `noisy-batch` プロセスが CPU を占有する | アプリのコードは変わっていないのに、全APIが一様に 3〜5 倍遅くなる | サービスビューのインフラ欄でホストの CPU が張り付いている。プロセス別の CPU で `noisy-batch`、コンテナ別で `noisy-neighbor` が上位 |

> `noisy-neighbor` は強度 85 前後が見せやすい値です。上限は 90 で、それ以上にはなりません。chaos API に 30 秒ほど応答がないと、ワーカーは自動で止まります。

各シナリオで Splunk のどの画面をどう辿るかは [docs/demo-script.md](docs/demo-script.md) にまとめています。

## API

| メソッドとパス | 内容 |
|---|---|
| `GET /api/categories` | カテゴリ一覧 |
| `GET /api/products?category=books` | カテゴリ別の商品一覧 |
| `GET /api/products/{id}` | 商品詳細（動的価格付き） |
| `GET /api/search?q=smart` | 商品検索 |
| `GET /api/recommendations?customerId=1` | おすすめ |
| `POST /api/orders` | 注文（`{"customerId":1,"items":[{"productId":3,"quantity":1}],"couponCode":"SPRING-10","paymentMethod":"card"}`） |
| `GET /api/orders/{id}` | 注文の参照 |
| `GET/PUT /chaos/api/scenarios[/{id}]`, `POST /chaos/api/reset` | 障害の制御 |

## Splunk 側で確認するポイント

- **APM → サービス `shop-app`**
  - エラー率とレイテンシーの推移
  - エンドポイント別の内訳
  - Runtime metrics（JVM のヒープと GC）
  - Infrastructure（ホスト `o11y-demo-host`）
  - Database Query Performance
  - AlwaysOn Profiling
  - 関連ログ
- **Tag Spotlight**: `customer.tier`、`payment.method`、`coupon.type`、`http.route` などで絞り込む
- **Infrastructure → Hosts → `o11y-demo-host`**: プロセス別の CPU（`process.cpu.utilization`、`process.executable.name`）を見る
- **ログ（Splunk Enterprise）**: `index=<SPLUNK_HEC_INDEX> sourcetype="otel:shop-app"`。各イベントに `trace_id` と `span_id` が付く

## Database Monitoring（クエリサンプルと実行計画）

[Splunk Database Monitoring](https://help.splunk.com/en/splunk-observability-cloud/monitor-databases/introduction-to-splunk-database-monitoring) で、PostgreSQL のクエリサンプル、上位クエリ、**実行計画（EXPLAIN）**を見られるようにしてあります。

| 場所 | 設定 |
|---|---|
| PostgreSQL（`docker-compose.yml`, `db/init/00-monitoring.sql`） | `shared_preload_libraries=pg_stat_statements`、拡張 `pg_stat_statements` を作成。監視ユーザー `otel` に `pg_monitor` とアプリのテーブルへの `SELECT` を付与（EXPLAIN に必要） |
| Collector（`collector/config.yaml`） | `postgresql` レシーバーで `db.server.query_sample` と `db.server.top_query` を有効化。`otlp_http/dbmon` で `https://ingest.<realm>.signalfx.com/v3/event` に送る。パイプラインは `metrics/dbmon` と `logs/dbmon` |
| Java エージェント（`docker-compose.yml`） | `OTEL_INSTRUMENTATION_SPLUNK_JDBC_ENABLED=true`。JDBC の各セッションの `application_name` に traceparent が入り、DB のクエリサンプルと APM のトレースが紐付く |

ローカルで Collector の出力を見て確認した内容:

- 上位クエリのイベントに `postgresql.query_plan`（EXPLAIN の JSON）が付く
- slow-query の `SELECT COALESCE(SUM(quantity), ?) FROM order_items WHERE product_id = ?` と、pool-exhaustion の順位集計クエリは、どちらもプランに `Seq Scan` が出る
- クエリサンプルの `postgresql.application_name` が `00-<trace_id>-<span_id>-03`（traceparent）になっている

注意点:

- `INSERT` や `UPDATE` の EXPLAIN は、読み取り専用の監視ユーザーでは `permission denied` になります。Collector のログに毎分 `failed to explain statement` が出ますが、想定どおりの挙動です。書き込み権限は付与していません
- 収集間隔と件数の上限は、Splunk の推奨どおり既定値のままにしています。上位クエリは 60 秒ごと、クエリサンプルは 10 秒ごとです

## トラブルシューティング

| 症状 | 確認すること |
|---|---|
| APM にサービスが出ない | `docker compose logs otel-collector` で realm とトークンのエラーが出ていないか |
| プロファイルが出ない | 同じログで `splunk_hec/profiling` の送信エラーが出ていないか。APM の AlwaysOn Profiling が有効か |
| ログが Splunk Enterprise に来ない | HEC の URL、トークン、index の権限。自己署名証明書なら `SPLUNK_HEC_TLS_INSECURE=true` |
| サービスビューにインフラのメトリクスが出ない | APM とホストメトリクスの `host.name` は、Collector がどちらも `DEMO_HOST_NAME` に揃えている。過去に別のホスト名で送ったデータが残っていないか |
| Database Monitoring にデータが出ない | ライセンスと realm の対応状況。`docker compose down -v` で DB を作り直したか。Collector のログに `postgresql` レシーバーの接続エラーが出ていないか |
| `docker_stats` のエラー | Docker Engine が 25 未満だと API 1.44 に対応していない。`collector/config.yaml` の `api_version` を下げる |
| `noisy-neighbor` を ON にしたら操作不能になった | 強度を下げる。最悪でも 30 秒で自動停止する。`docker compose stop noisy-neighbor` でも止められる |
