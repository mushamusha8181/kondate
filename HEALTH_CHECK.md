# 今日の献立 v1.3.0 コード健康診断

v1.2.0を全ファイル再監査し、追加修正した版です。

## 追加修正
- 新検索開始時、Futureだけでなく進行中のDeepSeek HTTP接続も切断。
- 同一検索サイクルで一度AI判定した候補を再判定しないよう重複処理を除去。
- 4ラウンド後に同じ条件が永久に検索不能になる状態を解消。次の明示的な「次へ」で再検索可能。
- YouTube WebView埋め込みのRefererを `https://<applicationId>` 形式へ修正。
- 履歴保存時の無意味なお気に入り照合を削除。
- APIキー復号時の全例外握りつぶしを廃止。暗号/Base64破損だけを処理し、壊れた該当値だけ削除。

## APIキー
実行時に必要なAPIキーはDeepSeekのみです。Android KeystoreのAES-256-GCM鍵で暗号化し、バックアップ禁止、ログ出力なし、APKへの直書きなしです。AuthorizationヘッダーはHTTPSの `api.deepseek.com` にだけ付与し、リダイレクトも無効です。

## 監視・常駐処理
常駐Service、BroadcastReceiver、WorkManager、定期ポーリング、AlarmManagerはありません。検索と画像取得はユーザー操作時だけです。

## 意図的に残す多段判定
ローカル候補確認 → DeepSeek厳格判定 → 原文根拠再照合は、無関係動画とAI推測を防ぐための多段防御であり、削除対象の重複処理ではありません。

## 未実施
この環境ではAndroid SDKを使った `assembleDebug` / `lintDebug` の完全実ビルド確認は未実施です。
