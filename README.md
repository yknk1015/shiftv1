# Shift Scheduler Demo

Java 17 / Spring Boot / SQLite を用いたシフト作成デモです。需要（デマンド）ブロックからシフトを割当し、従業員スキル・制約・ルール（週休など）を考慮します。

## 概要 / Overview

- 需要（デマンド）は「開始/終了/必要席数/スキル」で定義
- 従業員はスキルと制約（UNAVAILABLE/LIMITED/VACATION/SICK/PERSONAL など）を持ちます
- 任意で「短時間ペアリング（午前/午後→フル）」を有効化可能
- 管理者は同期生成/非同期生成の両APIを利用できます

## 生成ポリシー / Generation Policy

- 需要 requiredSeats を満たすよう、スキル適合者に割当
- 制約（UNAVAILABLE/LIMITED/VACATION/SICK/PERSONAL）は割当不可
- 週休（weeklyRestDays）: 週の勤務可能日数 = 7 - 週休日数（同一日の複数枠は1日として計上）
- 既存の手動割当は尊重（重複禁止）し、同一時間帯は座席数から差し引き

## 生成API / Stable APIs（POST / 要管理者）

以下はすべて管理者（ROLE_ADMIN）かつ POST での呼出が必要です。URL直打ちの GET は 302/405 になります。

- 同期（月）  
  `POST /api/schedule/generate/demand?year=YYYY&month=M&granularity=60&reset=true|false`  
  reset=true: 月の割当をクリアして作り直し／reset=false: 既存を残したまま空きのみ埋める
- 同期（日）  
  `POST /api/schedule/generate/demand/day?date=YYYY-MM-DD&reset=true|false`
- 非同期（月）  
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

## 文字化けの対処 / Encoding

テンプレートや本文に文字化けが残る場合は、UTF‑8へ統一してください。

- 一括変換スクリプト（Shift‑JIS→UTF‑8）: `tools/convert-templates-to-utf8.ps1`
  - 確認: `pwsh -File tools/convert-templates-to-utf8.ps1 -WhatIf`
  - 実行: `pwsh -File tools/convert-templates-to-utf8.ps1`
- 残存検索（「�」を含む行）:  
  `rg -n "�" src`
- よく使う日本語メッセージ例:  
  「スキルは必須です」「日付または曜日のいずれかを指定してください」  
  「開始時刻と終了時刻の指定が不正です」「スキルが見つかりません」

## 起動 / Getting Started

```
mvn spring-boot:run
```

初期ユーザー:

- 管理: `admin` / `admin123`
- 一般: `user` / `user123`

主なURL:

- Dashboard: http://localhost:8080/dashboard
- Calendar: http://localhost:8080/calendar
- Login: http://localhost:8080/login

## スタック / Stack

- Java 17, Spring Boot 3, Spring Security, Spring Data JPA
- SQLite, Thymeleaf, Maven

## 備考

- 生成は @Transactional で整合性を確保。問題があれば ErrorLogBuffer / DEBUG ログで診断可能。
