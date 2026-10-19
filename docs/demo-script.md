# デモ台本

どのシナリオも、次の3段階で進めます。

1. **発見**: サービスビューでエラーかレイテンシーの異常に気づく
2. **切り分け**: どのエンドポイントか、どの依存先か、アプリの中か外かを絞る
3. **原因**: リソース、GC、DBクエリ、関数、ログのどれかで根拠をつかむ

準備と注意:

- 開始前に `./chaosctl off all` を実行し、5〜10 分ほど正常な状態のデータを溜めておきます。比較の基準になります
- ON にしてから APM に反映されるまで 1〜2 分かかります。プロファイルとホストメトリクスは 10 秒単位で届きます
- 1回のデモで ON にする障害は1つにします。終わったら `./chaosctl off <id>` で戻します

---

## 1. GC しすぎ (`gc-pressure`)

```bash
./chaosctl on gc-pressure
```

1. **発見**: サービスビューで p90/p99 のレイテンシーがじわじわ上がる。特定のエンドポイントだけでなく全体的に遅い
2. **切り分け**: エンドポイント別で見ると `GET /api/products` が最も悪いが、ほかも引きずられている。この「全体が遅い」が GC を疑うきっかけになる
3. **原因**:
   - Runtime metrics: GC 時間と GC 回数が急増し、ヒープ使用量が高い位置で張り付く
   - AlwaysOn Profiling のメモリ（アロケーション）: `CatalogSnapshotBuilder.build` がアロケーションの大半を占める
   - CPU プロファイル: GC のスレッドや `byte[]` の確保が目立つ

## 2. 特定のコードが重い (`cpu-hotspot`)

```bash
./chaosctl on cpu-hotspot
```

1. **発見**: `GET /api/products/{id}` だけレイテンシーが 10ms から数百 ms に上がる
2. **切り分け**: トレースを開くと、DB スパンは短いのに、アプリ内の自己時間が長い
3. **原因**:
   - インフラ欄: ホストとプロセス（java）の CPU が上がっている
   - AlwaysOn Profiling（CPU）の flame graph で、`PriceCalculator.computeHistoricalPriceTrend` が最も幅広い。トレースから「このトレースの期間のプロファイル」に飛ぶのも効果的

## 3. クエリが遅い (`slow-query`)

```bash
./chaosctl on slow-query
```

1. **発見**: `GET /api/search` が約 2s になる
2. **切り分け**: トレースのウォーターフォールに、同じ `SELECT ... FROM order_items WHERE product_id = ?` が 20 回並ぶ（N+1）
3. **原因**:
   - Database Query Performance: 上記の SQL が実行回数と合計時間の両方で突出している
   - Collector の `postgresql` レシーバーのメトリクスで、DB 側の負荷（ブロック読み取りなど）も上がっている
   - 正体: `order_items.product_id` にインデックスがない。さらに非正規化済みの `sold_count` を使わず、毎回集計している

## 4. DB コネクションプール枯渇 (`pool-exhaustion`)

```bash
./chaosctl on pool-exhaustion
```

1. **発見**: 注文が 6s 前後に悪化するだけでなく、**関係のない商品一覧や検索まで**遅くなり、503 が出始める
2. **切り分け**: 遅いトレースを開くと、DB スパンの前に何もしていない時間がある（接続待ち）。エラーのスパンには `Connection is not available, request timed out after 2000ms`
3. **原因**:
   - Runtime metrics と接続プールのメトリクス（`db.client.connections.*`、pool `shop-pool`）: 使用中が上限の 10 に張り付き、接続待ちが発生している
   - 注文のトレース: トランザクション内で順位集計クエリ（`SELECT COUNT(*) + 1 FROM (SELECT customer_id, SUM(total) ...`）が走り、その後も数秒間接続を握っている
   - Database Query Performance: 順位集計クエリが重い。**遅いクエリと長いトランザクションの組み合わせが、接続プールを食い潰した**、という流れで説明できる

## 5. 外部 API の遅延とリトライの嵐 (`external-latency`)

```bash
./chaosctl on external-latency          # 遅延のみ（タイムアウトとリトライが混ざる）
./chaosctl set external-latency 70      # 決済がほぼ全滅し、502 が出る
```

1. **発見**: `POST /api/orders` が遅くなる。強度を上げるとエラー率も上がる
2. **切り分け**: Service Map で `shop-app → payment-gateway` のエッジのレイテンシーとエラーが悪化している。**自分のサービスの問題ではない**ことがすぐ分かる
3. **原因**:
   - トレース: `POST payment-gateway` のスパンが、1s のタイムアウトで最大 4 回連続して並ぶ（リトライの嵐）
   - ログ: `Payment attempt 2/4 failed: java.net.http.HttpTimeoutException`
   - 議論のポイント: バックオフもサーキットブレーカーもないリトライが、外部の障害を増幅している

## 6. ロック競合 (`lock-contention`)

```bash
./chaosctl on lock-contention
```

1. **発見**: 注文のレイテンシーが上がる
2. **切り分け**: **CPU は低いのに遅い**。DB スパンや外部呼び出しのスパンは短く、スパンとスパンの間に空白がある
3. **原因**:
   - AlwaysOn Profiling（スレッドのスタックサンプリング）: 多くのスレッドが `InventoryService.reserve` で BLOCKED になっている。ロックを持っているのは `syncWithLegacyWarehouse`
   - 「CPU 以外の何かを待っている」ことをプロファイルで示すデモになる

## 7. メモリリークから OOM (`memory-leak`)

```bash
./chaosctl on memory-leak          # 数分〜10 分で OOM
./chaosctl on memory-leak -i 100   # 約 90 秒で OOM（時間がないとき）
```

1. **発見**: 時間とともにレイテンシーが悪化する。最後はリクエストが失敗し、サービスが一瞬消えて戻る
2. **切り分け**: Runtime metrics のヒープが、GC 後も下がりきらず右肩上がりになる（ノコギリ波の底が上がっていく）。GC 時間も増える
3. **原因**:
   - メモリプロファイル: `PersonalizationCache.remember` のアロケーションが積み上がっている
   - コンテナが再起動した証拠: インフラ欄、`docker_stats`、ログの `Terminating due to java.lang.OutOfMemoryError`
   - 再起動すると障害は OFF に戻ります。「再起動で直ったように見えるが、根本原因は残っている」という話につなげられます

## 8. 変なログとエラー (`log-noise`)

```bash
./chaosctl on log-noise
```

1. **発見**: `POST /api/orders` のエラー率が上がる
2. **切り分け**: Tag Spotlight でエラーのあるリクエストを属性別に見ると、`coupon.type=legacy` にエラーが集中している。`customer.tier` や `payment.method` には偏りがない
3. **原因**:
   - エラートレースの関連ログ（Log Observer Connect）で、`Failed to resolve legacy coupon LEGACY:CMP-2023; campaign table is out of sync` とスタックトレース
   - 同じ時間帯に `FlagClient` の無意味な WARN が毎秒 100 行以上出ており、ログだけを見ていると本当の ERROR が埋もれる。**トレースから辿れば、関係するログだけに直行できる**ことを見せる

## 9. ノイジーネイバー (`noisy-neighbor`)

```bash
./chaosctl on noisy-neighbor
```

1. **発見**: 全エンドポイントが一様に 3〜5 倍遅くなる。デプロイもコードの変更もしていない
2. **切り分け**:
   - プロファイルに目立つホットスポットがない
   - GC も DB も正常
   - トレースはどのスパンも均等に間延びしている
3. **原因**:
   - サービスビューのインフラ欄: ホスト `o11y-demo-host` の CPU が張り付いている。一方、app 自身の CPU は増えていない
   - Infrastructure → ホスト → プロセス別の CPU（`process.cpu.utilization`）: `noisy-batch` が上位を占めている（コマンドラインは `python -u noisy_batch.py`）
   - `docker_stats` のコンテナ別の CPU でも `noisy-neighbor` が突出している
   - 「アプリの外に原因がある」ことを、APM からインフラまで一続きで辿れる点を見せる
