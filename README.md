# Shift Scheduler Demo

Java 17 / Spring Boot / SQLite を利用したシフト管理デモ。デマンド（需要）ブロックからシフトを自動生成し、制約・スキルを考慮します。

## 概要 / Overview

本アプリは「需要（デマンド）ベース」でブロック単位のシフトを自動生成します。

- 需要（デマンド）は時間帯ブロック（開始/終了/必要席数/スキル）で表現
- 従業員はスキル・日別の制約（UNAVAILABLE/LIMITED）を持ち、適合する時間帯のみ割当
- オプションで短時間ペアリング（午前・午後の組合せ→フルブロック）を実施
- 管理者は同期/非同期の生成APIを利用可能

## 生成ポリシー / Generation Policy

- デマンド行ごとに requiredSeats を満たす人数を割当（重複・時間重なりを回避）
- スキルが指定されているデマンドは、同スキル保有者のみ対象
- 制約（UNAVAILABLE: 終日不可 / LIMITED: 指定時間内のみ可）を尊重
- 短時間ペアリングが有効な場合、朝(例:09:00-13:00)と午後(13:00-18:00)を1名に結合してフル(09:00-18:00)として割当を優先

## 正式API / Stable APIs

管理者権限（ROLE_ADMIN）が必要です。

- POST `/api/schedule/generate/demand` ・・・ 月単位の同期生成（パラメータ: `year, month, granularity=60, reset=true`）
- POST `/api/schedule/generate/demand/day` ・・・ 1日の同期生成（`date`, `reset=true`）
- POST `/api/schedule/generate/demand/async` ・・・ 月単位の非同期生成（`year, month, granularity=60, reset=true`）

補助API:

- GET `/api/schedule/stats/monthly?year=YYYY&month=M` ・・・ 月次統計
- GET `/api/admin/error-logs` ・・・ 直近のエラーログ（生成失敗や未割当日の記録）

既存の旧パス（`/generate-from-demand*`）は当面の互換のため残していますが、正式APIの利用を推奨します。

## 管理画面（デバッグ）

- `/admin-debug` ・・・ 生成APIの実行と結果を画面で確認できます（管理者向け）

## 起動 / Getting Started

```
mvn spring-boot:run
```

既定のユーザー（デモ）:

- 管理: `admin` / `admin123`
- 一般: `user` / `user123`

主要画面:

- Dashboard: http://localhost:8080/dashboard
- Calendar: http://localhost:8080/calendar
- Login: http://localhost:8080/login

## 技術スタック

- Java 17, Spring Boot 3, Spring Security, Spring Data JPA
- SQLite, Thymeleaf, Maven

## その他

- 月次取得は空結果をキャッシュしません（生成直後の反映を確実にするため）
- 非同期生成はトランザクション境界を確保（@Transactional）
- 需要のある日に未割当ならエラーバッファに記録、DEBUGログで日別の席数・生成件数を出力
