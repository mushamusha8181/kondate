# 今日の献立 (Android)

小さめのAndroidスマホで、条件に合うYouTubeの簡単料理を1件ずつ表示し、「次へ」で別の候補へ進むアプリです。

現在のソース版: **1.2.0 (versionCode 3)**

## v1.2.0 の重要変更

外部APIを **DeepSeekだけ** に一本化しました。

- YouTube Data API v3を完全に撤去
- YouTube APIキー入力欄を削除
- Google Cloud / SHA-1 / `X-Android-Package` / `X-Android-Cert`処理を削除
- `YouTubeClient.kt`を削除
- YouTube検索はDeepSeek Search APIの `site:youtube.com` 検索を使用
- 候補の厳格判定もDeepSeek `deepseek-flash`を使用
- YouTube動画の表示自体は公開URL / 埋め込みプレーヤーを使用し、YouTube APIキーは不要

旧v1.1.0から更新した端末では、以前保存していたYouTube APIキーを初回起動時に1回だけ削除します。

## 検索の流れ

1. 選択中の時間・ジャンル・調理方法・食材から検索語を作成
2. `POST https://api.deepseek.com/v1/tools/search` で `site:youtube.com` に限定して検索
3. DeepSeek Searchのrerank score 1.5未満を除外
4. YouTube動画URLだけを抽出
5. 明らかに料理でない候補をローカルで除外
6. 候補を `deepseek-flash` へ渡して厳格判定
7. 調理時間・カテゴリ・調理方法・材料・手順の根拠が検索結果内に存在するかアプリ側でも再照合
8. 全条件を通った候補だけ表示

条件に合う候補がない場合、無関係な動画を代わりに表示しません。

## 調理時間

`3分 / 5分 / 10分` は動画の再生時間ではなく **料理が完成するまでの調理時間上限** です。

- 「3分30秒」は4分扱い
- 「3分半」は4分扱い
- 調理時間の根拠が確認できない候補は不採用

## DeepSeek

必要なAPIキーはDeepSeekだけです。

使用エンドポイント:

- Search: `https://api.deepseek.com/v1/tools/search`
- Chat: `https://api.deepseek.com/chat/completions`

Chatモデル:

- `deepseek-flash`
- thinking disabled
- JSON Output
- temperature 0

Searchでは最大12件を取得し、検索結果URLからYouTube動画IDを確認できた候補だけを判定します。検索条件を変えず「次へ」を押した場合は、すでに判定済みの候補キューを優先して使うため、毎回検索APIを呼びません。

## AIの誤判定対策

DeepSeekの回答だけを信用して画面表示する構造にはしていません。

- 検索結果中の命令文をプロンプトとして実行しないよう明示
- 材料・手順・時間・カテゴリ・調理方法は検索結果内に存在する文字列だけを採用
- AIが返した根拠をアプリ側で再照合
- 調理時間は根拠文からアプリ側でも再計算
- 使いたい食材は最終材料欄にも存在することを確認
- 使わない食材が最終材料欄に含まれる候補は不採用
- confidence 0.95未満は不採用
- DeepSeekのJSONが壊れている、空、途中終了の場合は正常候補として消費しない

## 検索候補の重複回避

- 閲覧済みID: 最大300件
- 履歴: 最大100件
- お気に入り: 最大200件
- 同じ動画を連続表示しない
- 同一条件の候補はメモリ上のキューに保持
- Search結果は4種類の検索表現を順に試し、同じ検索を無限反復しない
- AI判定が一時失敗した場合はSearch結果をメモリに保持し、再試行時に不要な再検索をしない

## YouTube表示

動画はYouTube埋め込みプレーヤーで表示します。

- YouTube APIキー不要
- WebViewへ `Referer: android-app://jp.mushamusha.kyounokondate` を付与
- ユーザー操作によるYouTubeリンクは外部のYouTubeアプリ / ブラウザへ渡す
- 「YouTubeで開く」ボタンを常時表示
- WebView生成に失敗した場合はサムネイル表示へフォールバック
- 画面を離れた場合はWebViewをpauseし、不要になったWebViewはdestroy

サムネイルは動画IDから `i.ytimg.com` の公開サムネイルURLを組み立てて表示します。

## APIキー保護

- APK / BuildConfigへAPIキーを埋め込まない
- ユーザーが設定画面から入力
- Android KeystoreのAES-256-GCM鍵で暗号化
- 暗号文だけをアプリ専用SharedPreferencesへ保存
- 保存済みキーは画面へ再表示しない
- APIキー設定画面は `FLAG_SECURE`
- Autofill / IME個人学習 / View状態保存を抑止
- `android:allowBackup="false"`
- data extraction rulesでも`secure_keys.xml`を除外
- AuthorizationヘッダーやAPIキーをログするコードなし
- DeepSeekキー付き通信先は `api.deepseek.com` のHTTPSだけ
- HTTPリダイレクトは追わない

root化端末やOS自体の侵害まで含めた絶対的な秘密保持は保証できません。

## 通信キャンセル

条件変更や画面遷移時に古い検索が残らないよう、Futureのキャンセルだけでなく、処理中のHTTP接続も切断します。サムネイル取得も画面切替時に切断します。

## ホーム画面

小さいスマホを前提に、普段の操作を絞っています。

上部横スクロール:

- 時間: `3分 / 5分 / 10分`
- 種類: `主菜 / 副菜 / 丼 / 麺 / ご飯 / おつまみ`
- 調理方法: `なんでも / レンジ / フライパン / 火なし`
- 表示: `動画 / 画像`

下部:

- `♡`
- `次へ`
- `食材`

同じ選択済みチップをもう一度押しても無駄な再検索を行いません。画面回転やActivity再生成時も、表示中の献立とフィルターを復元してAPIの再検索を避けます。

食材指定は最大10項目、1項目40文字に正規化します。

## Android権限

Manifestで要求する権限は次だけです。

- `android.permission.INTERNET`

通常権限なのでOSの実行時許可ダイアログはありません。初回起動時にはその点を説明します。

要求しないもの:

- カメラ
- マイク
- 位置情報
- ストレージ / 写真
- 連絡先
- 通知
- Bluetooth

## ビルド環境

- Android Gradle Plugin 9.4.1
- Gradle 9.6.0
- compileSdk / targetSdk 37
- minSdk 26
- Java 17
- AGP 9系 built-in Kotlin

この実行環境にはAndroid SDK / Gradle実行環境が揃っていないため、`assembleDebug` / `lintDebug`の実ビルドは未実施です。

Android Studioで開いた後、最終確認してください。

```bash
./gradlew assembleDebug
./gradlew lintDebug
```

Gradle Wrapper JARがない場合は、同梱の `bootstrap-gradle.sh` / `bootstrap-gradle.ps1` を利用できます。
