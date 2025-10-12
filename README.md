# シフト管理システム

Java 17 / Spring Boot / SQLite を利用した本格的なシフト管理システムです。従業員のシフトを自動生成・管理し、直感的なWeb UIで操作できます。

## 🚀 主な機能

### 🔐 セキュリティ機能
- **ユーザー認証**: ログイン・ログアウト機能
- **権限管理**: 管理者・一般ユーザーの役割分離
- **セキュアなAPI**: Spring Securityによる保護

### 📅 シフト管理
- **自動シフト生成**: 公平なラウンドロビン方式
- **カレンダー表示**: 視覚的なシフト確認
- **柔軟な設定**: シフト時間・従業員数のカスタマイズ
- **統計機能**: 月次統計・従業員別勤務量

### 👥 従業員管理
- **CRUD操作**: 従業員の追加・編集・削除
- **役職管理**: 役職別のシフト割り当て

### 📊 データ管理
- **データエクスポート**: CSV形式での出力
- **バックアップ機能**: データベースの完全バックアップ
- **統計情報**: 詳細なデータ分析

## 🛠 技術スタック
- **Java 17**
- **Spring Boot 3.2.5**
- **Spring Security**
- **Spring Data JPA**
- **SQLite**
- **Thymeleaf**
- **Maven**

## 📋 必要環境
- Java 17
- Maven 3.9 以降

## 🚀 クイックスタート

### 1. アプリケーションの起動
```bash
mvn spring-boot:run
```

### 2. アクセス
起動後、以下のURLでアクセスできます：
- **ダッシュボード**: http://localhost:8080/dashboard
- **カレンダー**: http://localhost:8080/calendar
- **ログイン**: http://localhost:8080/login

### 3. デフォルトアカウント
- **管理者**: `admin` / `admin123`
- **一般ユーザー**: `user` / `user123`

## 📖 使用方法

### 基本的な操作フロー
1. **ログイン**: デフォルトアカウントでログイン
2. **従業員初期化**: ダッシュボードで従業員データを作成
3. **シフト生成**: カレンダーまたはダッシュボードでシフトを生成
4. **確認**: カレンダーでシフトを視覚的に確認

### カレンダー機能
- **月間表示**: 月単位でのシフト確認
- **色分け**: シフトタイプ別の色分け表示
  - 🟢 朝シフト (9:00-15:00)
  - 🟡 夜シフト (15:00-21:00)
  - 🔴 土日シフト (9:00-18:00)
- **ナビゲーション**: 前月・次月への移動

## 🔌 API エンドポイント

### 認証
- `POST /api/auth/login` - ログイン
- `POST /api/auth/logout` - ログアウト
- `POST /api/auth/register` - ユーザー登録
- `GET /api/auth/me` - 現在のユーザー情報

### シフト管理
- `POST /api/schedule/generate` - シフト生成
- `GET /api/schedule` - シフト取得
- `GET /api/schedule/stats/monthly` - 月次統計
- `GET /api/schedule/stats/employee-workload` - 従業員別勤務量

### 従業員管理
- `GET /api/employees` - 全従業員取得
- `POST /api/employees` - 従業員作成
- `PUT /api/employees/{id}` - 従業員更新
- `DELETE /api/employees/{id}` - 従業員削除

### 設定管理
- `GET /api/config/shift` - シフト設定一覧
- `POST /api/config/shift` - シフト設定作成
- `PUT /api/config/shift/{id}` - シフト設定更新

### データ管理
- `GET /api/data/export/employees/csv` - 従業員データエクスポート
- `GET /api/data/export/schedule/csv` - シフトデータエクスポート
- `POST /api/data/backup/export` - データベースバックアップ

### 管理機能
- `GET /api/admin/status` - システム状態確認
- `POST /api/admin/initialize-employees` - 従業員初期化
- `DELETE /api/admin/reset-employees` - 従業員リセット

## 💾 データベース
`shift-demo.db` という SQLite ファイルがプロジェクト直下に作成されます。

## 🔧 カスタマイズ

### シフト設定の変更
1. ダッシュボードの「設定管理」セクションを使用
2. または `/api/config/shift` API を直接呼び出し

### デフォルト設定
- **朝シフト**: 9:00-15:00 (4名)
- **夜シフト**: 15:00-21:00 (4名)
- **土日シフト**: 9:00-18:00 (5名)

## 🛡️ セキュリティ
- 管理者のみアクセス可能な機能
- セッション管理
- CSRF保護
- 入力値検証

## 📈 今後の拡張予定
- 祝日データの対応
- 従業員の希望シフト入力機能
- シフト変更申請・承認機能
- メール通知機能
- モバイルアプリ対応

## 🤝 サポート
質問や問題がある場合は、GitHubのIssuesでお知らせください。