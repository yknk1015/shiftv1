# シフト自動作成デモアプリ（学習用強化版）

Java 17 / Spring Boot / SQLite を利用した学習用のシフト自動作成ツールです。多様な属性を持つ従業員に対して、より現実的で柔軟なシフト生成を行います。

## 🚀 主な特徴

### 従業員モデルの拡張
- **スキルレベル** (1-5段階): 高いスキルの従業員を優先的に割り当て
- **土日勤務可能**: 土日勤務の可否を個別設定
- **夜勤可能**: 夜勤シフトの可否を個別設定
- **希望勤務日数**: 週あたりの希望勤務日数を設定
- **役職**: スタッフ、リーダー、マネージャー、アシスタント

### 柔軟なシフト設定
- **動的設定変更**: 勤務時間や必要人数を実行時に変更可能
- **平日シフト**: 午前・午後の2シフト制
- **休日シフト**: 単一シフト制
- **バリデーション**: 従業員の制約を考慮した割り当て

### 高度なアルゴリズム
- **スキル優先**: 高いスキルレベルの従業員を優先
- **制約遵守**: 土日勤務不可、夜勤不可などの制約を考慮
- **公平性**: ラウンドロビン方式で公平な配分
- **エラーハンドリング**: 詳細なエラーメッセージとログ出力

## 🛠️ 必要環境
- Java 17
- Maven 3.9 以降

## 🚀 ビルド & 実行
```bash
mvn spring-boot:run
```

起動後、`http://localhost:8080` でアプリが待ち受けます。

## 📡 API エンドポイント

### シフト管理
```bash
# シフト生成
POST /api/schedule/generate?year=2024&month=7

# シフト取得
GET /api/schedule?year=2024&month=7

# 生成統計情報
GET /api/schedule/generation-stats?year=2024&month=7
```

### 設定管理
```bash
# 現在のシフト設定を取得
GET /api/schedule/configuration

# シフト設定を更新
PUT /api/schedule/configuration
Content-Type: application/json

{
  "weekdayAmStart": "09:00",
  "weekdayAmEnd": "15:00",
  "weekdayPmStart": "15:00",
  "weekdayPmEnd": "21:00",
  "weekdayEmployeesPerShift": 4,
  "weekendStart": "09:00",
  "weekendEnd": "18:00",
  "weekendEmployeesPerShift": 5
}
```

### 統計情報
```bash
# 月次統計
GET /api/schedule/stats/monthly?year=2024&month=7

# 従業員別勤務量
GET /api/schedule/stats/employee-workload?year=2024&month=7

# シフト種別分布
GET /api/schedule/stats/shift-distribution?year=2024&month=7
```

### 従業員管理
```bash
# 従業員一覧
GET /api/employees

# 従業員追加
POST /api/employees
Content-Type: application/json

{
  "name": "新規従業員",
  "role": "スタッフ",
  "skillLevel": 3,
  "canWorkWeekends": true,
  "canWorkEvenings": true,
  "preferredWorkingDays": 5
}
```

## 🧪 テスト実行
```bash
# 全テスト実行
mvn test

# 特定のテストクラス実行
mvn test -Dtest=ScheduleServiceTest
```

## 📊 学習ポイント

### 1. オブジェクト指向設計
- **Builder パターン**: ShiftConfiguration での設定構築
- **Strategy パターン**: 従業員適性判定ロジック
- **Repository パターン**: データアクセス層の分離

### 2. Spring Boot 機能
- **バリデーション**: Bean Validation による入力検証
- **トランザクション**: @Transactional によるデータ整合性
- **ログ出力**: SLF4J による構造化ログ
- **テスト**: Spring Boot Test による統合テスト

### 3. アルゴリズム学習
- **制約充足問題**: 従業員制約を考慮したシフト割り当て
- **優先度付き割り当て**: スキルレベルによる優先順位
- **公平性アルゴリズム**: ラウンドロビン方式の実装

### 4. データベース設計
- **JPA/Hibernate**: エンティティマッピング
- **リレーション**: Employee と ShiftAssignment の関連
- **SQLite**: 軽量データベースの活用

## 📁 プロジェクト構造
```
src/main/java/com/example/shiftv1/
├── employee/           # 従業員管理
│   ├── Employee.java   # 従業員エンティティ（拡張版）
│   └── ...
├── schedule/           # シフト管理
│   ├── ScheduleService.java      # シフト生成ロジック（改善版）
│   ├── ShiftConfiguration.java   # 設定管理クラス（新規）
│   └── ...
└── exception/          # 例外処理
    └── GlobalExceptionHandler.java
```

## 🔧 カスタマイズ例

### シフト設定の変更
```java
// カスタム設定を作成
ShiftConfiguration customConfig = ShiftConfiguration.builder()
    .weekdayAmStart(LocalTime.of(8, 0))
    .weekdayAmEnd(LocalTime.of(16, 0))
    .weekdayEmployeesPerShift(3)
    .build();

// 設定を適用
scheduleService.updateShiftConfiguration(customConfig);
```

### 従業員制約の追加
```java
// 新しい従業員を作成
Employee newEmployee = new Employee(
    "新規スタッフ", 
    "スタッフ", 
    2,              // スキルレベル
    false,          // 土日勤務不可
    true,           // 夜勤可能
    4               // 希望勤務日数
);
```

## 📈 今後の拡張案
- **祝日対応**: 日本の祝日データとの連携
- **希望シフト**: 従業員の希望入力機能
- **最適化アルゴリズム**: より高度な最適化手法の導入
- **Web UI**: フロントエンドでの操作画面
- **レポート機能**: より詳細な分析・レポート機能

## 📘 学習用バージョンアップガイド

学習コンテンツとして段階的にレベルアップさせるためのロードマップや課題設計のヒントをまとめた「[学習用シフト作成プログラム強化ガイド](docs/training_program_upgrade.md)」を参照してください。
