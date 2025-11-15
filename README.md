# Shift Scheduler Demo
Java 17 / Spring Boot / SQLite を用いたシフト作成デモです。
需要（デマンド）ブロックからシフトを割当し、従業員スキル・制約・週休ルールなどを考慮します。
UTF-8 で統一しています。

## 概要 / Overview

- 需要（デマンド）は 開始/終了/必要席数/スキル で定義
- 従業員は スキル と 制約（UNAVAILABLE / LIMITED / VACATION / SICK / PERSONAL） を保持
- 短時間ペアリング（午前＋午後 → フル） を任意で有効化
- 管理者は 同期/非同期生成 API を利用可能

## 画面構成 / UI Highlights

- **ダッシュボード**: 月次KPI（総シフト・稼働人数）＋ FREE/休日テーブル。生成状況と不足傾向を一目で確認。各画面に「戻る」導線を共通配置。
- **需要管理（/demand）**: 日次ビュー・週次テンプレ・月次プランを一画面で切替。臨時需要ブーストやタイムラインビューも搭載。
- **スケジュールエディタ**: 表ビュー／日別カードビューを切替。従業員・スキル・日付でフィルタ可能。
- **CSVエクスポート**: UTF-8+BOM、日本語ヘッダー。FREE/休日は 00:00-00:00 で出力。稼働時間(分)・区分・スキル名を含み、そのまま資料化可能。

## 試し方 / Quick Try

1. `/demand` で臨時需要を追加。
2. `/dashboard` の「最新シフト取得」でサマリー・シフト一覧が更新されることを確認。
3. `/demand-supply` で同期間を指定し、ヒートマップが需要変動に追従するか確認（15/30/60分で粒度変更可）。
4. `POST /api/schedule/export/csv` で CSV を取得し、Excel で文字化けなく開けることを確認

## 生成ポリシー / Generation Policy

- 需要 requiredSeats を満たすよう、スキル適合者に割当
- 制約（UNAVAILABLE など）は割当不可
- 週休（weeklyRestDays）: 週の勤務可能日数 = 7 − 週休日数（同日の複数枠は1日扱い）
- 既存の手動割当は尊重し、同一時間帯は座席数から差し引く

## 生成API / Stable APIs（POST / 要管理者）

以下はすべて管理者（ROLE_ADMIN）かつ POST での呼出が必要です。URL 直打ち GET は 302/405 になります。

- 同期生成（月）  
  `POST /api/schedule/generate/demand?year=YYYY&month=M&granularity=60&reset=true|false`  
  reset=true: 既存をクリアして再生成／reset=false: 既存を残し、空きのみ埋める
- 同期生成（日）  
  `POST /api/schedule/generate/demand/day?date=YYYY-MM-DD&reset=true|false`
- 非同期生成（月） 
  `POST /api/schedule/generate/demand/async?year=YYYY&month=M&granularity=60&reset=true|false`

参考API:

- 月次統計: `GET /api/schedule/stats/monthly?year=YYYY&month=M`
- 直近エラーログ: `GET /api/admin/error-logs?limit=50`

### 認証付きの curl 例（PowerShell）

```
# 1) ログイン（Cookie保存）
curl.exe -sS -i -c cookies.txt -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' -X POST http://localhost:8080/api/auth/login

# 2) 月の同期生成（空きのみ埋める）
curl.exe -sS -i -b cookies.txt -X POST "http://localhost:8080/api/schedule/generate/demand?year=2025&month=11&granularity=60&reset=false"

# 3) 結果確認
curl.exe -sS -b cookies.txt "http://localhost:8080/api/schedule?year=2025&month=11"
```

## デバッグ / Debugging

- `/admin-debug` で生成/統計/スナップショット等を実行可能（管理者）
- 例外は Global エラーハンドラと ErrorLogBuffer に記録。必要に応じて  
  `logging.level.com.example.shiftv1.schedule=DEBUG` を有効化

## 文字コード / Encoding

テンプレートは UTF-8 固定です。誤って別の文字コードになった場合は `rg -n "�" src` などで確認してください。よく使う日本語メッセージ例:

> 「スキルは必須です」「日付または曜日のいずれかを指定してください」  
> 「開始時刻と終了時刻の指定が不正です」「スキルが見つかりません」

## 起動 / Getting Started

```
mvn spring-boot:run
```

初期ユーザー（管理者のみ）:

- `admin` / `admin123`

主なURL:

- Dashboard: http://localhost:8080/dashboard
- Calendar: http://localhost:8080/calendar
- Login: http://localhost:8080/login
- Demand vs Supply Heatmap: http://localhost:8080/demand-supply

## 需要 vs 供給ビュー

休憩を含む時間帯別の需要（requiredSeats）と、シフト生成後の供給（稼働中・休憩中・FREEフォロー可能枠）を比較する画面を追加しました。

- `GET /api/analytics/demand-supply?start=YYYY-MM-DD&end=YYYY-MM-DD&granularity=60&skillIds=1,2`
  - 1〜31日分の範囲で時間粒度（15/30/60分）を指定可能
  - 需要は DemandInterval を時間スロットに分解して集計
  - 供給は ShiftAssignment + BreakPeriod を参照し、休憩重複分を差し引いた実稼働FTEを算出
  - FREE勤務(`isFree=true`)は通常供給には含めず、休憩不足/純不足をフォローできる潜在数として別配列を返却
  - レスポンス例は `/demand-supply` 画面の Network タブから確認できます

UI では以下を確認できます。

- 各時間帯ごとの「需要 / 稼働供給 / 休憩中 / 不足（休憩由来 or 純不足）」のヒートマップ
- FREE 勤務者が休憩由来の不足をどこまでフォローできるか、および純不足に転用できる残量
- 最大不足時間帯や参照された割当前数などのサマリーカード

`/schedule-editor` のヘッダーからも新ビューへのショートカットを追加しています。

## スタック / Stack

- Java 17, Spring Boot 3, Spring Security, Spring Data JPA
- SQLite, Thymeleaf, Maven

## 技術メモ

- 生成は @Transactional で整合性を確保。問題があれば ErrorLogBuffer / DEBUG ログで診断可能。
- CSVエクスポートは UTF-8+BOM・日本語ヘッダーで出力され、FREE/休日プレースホルダーは `00:00-00:00` で統一しています。
  - 曜日・区分（通常/FREE/休日/休暇）・担当者スキル・稼働時間(分)を含むため、外部レポートにも流用可能です。
