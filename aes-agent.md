---
name: Master Key Cypher Provider
overview: Добавление нового CypherProvider на базе master-key с использованием отдельного процесса aes-agent, который хранит ключ в памяти и предоставляет gRPC API для шифрования/дешифрования. Сессия ограничена 15 минутами, ключ вводится только в начале сессии.
todos:
  - id: add-proto-plugins
    content: Добавить плагины для proto компиляции в project/plugins.sbt (sbt-protoc, ScalaPB)
    status: pending
  - id: create-proto-project
    content: Создать подпроект aesAgentProto в build.sbt с настройкой ScalaPB
    status: pending
  - id: create-proto-file
    content: Создать aesAgentProto/src/main/proto/aes_agent.proto с определением gRPC сервиса
    status: pending
  - id: create-agent-project
    content: Создать подпроект aesAgent в build.sbt с зависимостью от aesAgentProto
    status: pending
  - id: implement-agent-app
    content: Реализовать AesAgentApp - главный класс aes-agent с gRPC сервером
    status: pending
  - id: implement-agent-service
    content: Реализовать AesAgentService с методами шифрования/дешифрования AES-256
    status: pending
  - id: implement-agent-session
    content: Реализовать SessionManager для управления ключом и таймаутом в aes-agent
    status: pending
  - id: add-grpc-deps
    content: Добавить gRPC зависимости в project/Settings.scala и обновить alpasso зависимости
    status: pending
  - id: extend-cypher-alg
    content: Расширить CypherAlg enum, добавить MasterKey вариант и обновить сериализацию
    status: pending
  - id: key-input
    content: Реализовать KeyInput для безопасного ввода master key
    status: pending
  - id: agent-manager
    content: Создать AesAgentManager для управления жизненным циклом aes-agent процесса
    status: pending
  - id: master-key-impl
    content: Реализовать MasterKeyImpl в CypherService с gRPC клиентом
    status: pending
  - id: update-provisioner
    content: Обновить CypherProvisioner для поддержки MasterKey
    status: pending
  - id: update-command
    content: Обновить Command.make для создания MasterKey CypherService
    status: pending
  - id: update-argparser
    content: Добавить --master-key опцию в ArgParser для repo init
    status: pending
  - id: extend-errors
    content: Расширить CypherErr enum новыми типами ошибок
    status: pending
isProject: false
---

# План реализации Master Key Cypher Provider

## Архитектура

Новый CypherProvider будет использовать отдельный процесс `aes-agent`, который:

- Хранит AES-256 ключ в памяти
- Предоставляет gRPC API для шифрования/дешифрования
- Автоматически запускается при начале сессии
- Имеет таймаут сессии 15 минут

## Структура подпроектов

Проект будет состоять из трех подпроектов:

1. **aesAgentProto** - подпроект для proto файлов и сгенерированного кода
2. **aesAgent** - подпроект для реализации aes-agent процесса
3. **alpasso** - основной проект (уже существует), будет зависеть от aesAgentProto

## Компоненты для реализации

### 1. Создание подпроекта для proto файлов

**Файл**: `build.sbt`

- Создать подпроект `aesAgentProto`:
  - Включить плагин `ScalaPB` для компиляции proto файлов
  - Настроить генерацию Scala кода из proto
  - Настроить генерацию gRPC сервисов
  - Расположение: `aesAgentProto/src/main/proto/`

**Новый файл**: `aesAgentProto/src/main/proto/aes_agent.proto`

- Определить сервис `AesAgentService` с методами:
  - `Initialize(InitializeRequest) -> InitializeResponse` - инициализация с ключом
  - `Encrypt(EncryptRequest) -> EncryptResponse` - шифрование данных
  - `Decrypt(DecryptRequest) -> DecryptResponse` - дешифрование данных
  - `HealthCheck(HealthCheckRequest) -> HealthCheckResponse` - проверка состояния
- Определить сообщения для запросов/ответов

## Описание gRPC протокола

### Общая информация

Протокол использует gRPC для коммуникации между `alpasso` (клиент) и `aes-agent` (сервер). Сервер слушает на локальном порту (по умолчанию 50051) или unix socket.

### Структура proto файла

```protobuf
syntax = "proto3";

package aesagent;

option java_package = "aesagent.proto";
option java_outer_classname = "AesAgentProto";

// Сервис для работы с AES шифрованием
service AesAgentService {
  // Инициализация сессии с master key
  rpc Initialize(InitializeRequest) returns (InitializeResponse);
  
  // Шифрование данных
  rpc Encrypt(EncryptRequest) returns (EncryptResponse);
  
  // Дешифрование данных
  rpc Decrypt(DecryptRequest) returns (DecryptResponse);
  
  // Проверка состояния сессии
  rpc HealthCheck(HealthCheckRequest) returns (HealthCheckResponse);
}

// Запрос на инициализацию сессии
message InitializeRequest {
  // Master key в формате bytes (32 байта для AES-256)
  bytes master_key = 1;
  
  // Таймаут сессии в секундах (по умолчанию 900 = 15 минут)
  uint32 session_timeout_seconds = 2;
}

// Ответ на инициализацию
message InitializeResponse {
  // Статус инициализации
  Status status = 1;
  
  // Сообщение об ошибке (если status != SUCCESS)
  string error_message = 2;
  
  // Идентификатор сессии (для отслеживания)
  string session_id = 3;
}

// Запрос на шифрование
message EncryptRequest {
  // Данные для шифрования (plaintext)
  bytes data = 1;
}

// Ответ на шифрование
message EncryptResponse {
  // Статус операции
  Status status = 1;
  
  // Зашифрованные данные (ciphertext)
  bytes encrypted_data = 2;
  
  // Сообщение об ошибке (если status != SUCCESS)
  string error_message = 3;
}

// Запрос на дешифрование
message DecryptRequest {
  // Зашифрованные данные (ciphertext)
  bytes encrypted_data = 1;
}

// Ответ на дешифрование
message DecryptResponse {
  // Статус операции
  Status status = 1;
  
  // Расшифрованные данные (plaintext)
  bytes data = 2;
  
  // Сообщение об ошибке (если status != SUCCESS)
  string error_message = 3;
}

// Запрос на проверку состояния
message HealthCheckRequest {
  // Пустое сообщение
}

// Ответ на проверку состояния
message HealthCheckResponse {
  // Статус сессии
  SessionStatus session_status = 1;
  
  // Оставшееся время сессии в секундах (если сессия активна)
  uint32 remaining_seconds = 2;
  
  // Сообщение об ошибке (если session_status != ACTIVE)
  string error_message = 3;
}

// Статус операции
enum Status {
  SUCCESS = 0;              // Операция успешна
  ERROR = 1;                 // Общая ошибка
  SESSION_NOT_INITIALIZED = 2;  // Сессия не инициализирована
  SESSION_EXPIRED = 3;      // Сессия истекла
  INVALID_KEY = 4;          // Неверный ключ
  INVALID_DATA = 5;         // Неверные данные для шифрования/дешифрования
  ENCRYPTION_ERROR = 6;     // Ошибка при шифровании
  DECRYPTION_ERROR = 7;     // Ошибка при дешифровании
}

// Статус сессии
enum SessionStatus {
  ACTIVE = 0;               // Сессия активна
  NOT_INITIALIZED = 1;      // Сессия не инициализирована
  EXPIRED = 2;              // Сессия истекла
}
```

### Описание методов

#### 1. Initialize

**Назначение**: Инициализация сессии с master key.

**Запрос**:

- `master_key` (bytes): AES-256 ключ (32 байта). Должен быть передан один раз при старте сессии.
- `session_timeout_seconds` (uint32): Таймаут сессии в секундах. По умолчанию 900 (15 минут).

**Ответ**:

- `status` (Status): Статус инициализации:
  - `SUCCESS` - сессия успешно инициализирована
  - `INVALID_KEY` - ключ имеет неверный размер (не 32 байта)
  - `ERROR` - другая ошибка
- `error_message` (string): Описание ошибки, если статус не SUCCESS
- `session_id` (string): Уникальный идентификатор сессии для логирования/отладки

**Поведение**:

- Метод может быть вызван только один раз за время жизни процесса
- При повторном вызове возвращается ошибка `SESSION_NOT_INITIALIZED` или `ERROR`
- После успешной инициализации запускается таймер сессии
- Ключ хранится в памяти процесса и никогда не логируется

#### 2. Encrypt

**Назначение**: Шифрование данных с использованием AES-256.

**Запрос**:

- `data` (bytes): Plaintext данные для шифрования. Может быть любого размера.

**Ответ**:

- `status` (Status): Статус операции:
  - `SUCCESS` - данные успешно зашифрованы
  - `SESSION_NOT_INITIALIZED` - сессия не инициализирована (нужно вызвать Initialize)
  - `SESSION_EXPIRED` - сессия истекла
  - `ENCRYPTION_ERROR` - ошибка при шифровании
  - `INVALID_DATA` - неверные данные (например, пустые)
- `encrypted_data` (bytes): Зашифрованные данные (ciphertext). Формат: IV (16 байт) + зашифрованные данные
- `error_message` (string): Описание ошибки

**Поведение**:

- Используется AES-256 в режиме CBC или GCM
- Для каждого шифрования генерируется новый IV (Initialization Vector)
- IV добавляется в начало зашифрованных данных
- Метод потоковый - может обрабатывать данные любого размера

#### 3. Decrypt

**Назначение**: Дешифрование данных с использованием AES-256.

**Запрос**:

- `encrypted_data` (bytes): Зашифрованные данные (ciphertext). Должен содержать IV в начале.

**Ответ**:

- `status` (Status): Статус операции:
  - `SUCCESS` - данные успешно расшифрованы
  - `SESSION_NOT_INITIALIZED` - сессия не инициализирована
  - `SESSION_EXPIRED` - сессия истекла
  - `DECRYPTION_ERROR` - ошибка при дешифровании (неверный формат, поврежденные данные)
  - `INVALID_DATA` - неверные данные (слишком короткие для IV)
- `data` (bytes): Расшифрованные данные (plaintext)
- `error_message` (string): Описание ошибки

**Поведение**:

- Извлекает IV из начала зашифрованных данных
- Использует тот же ключ, что был установлен при Initialize
- Проверяет целостность данных (если используется GCM)

#### 4. HealthCheck

**Назначение**: Проверка состояния сессии и оставшегося времени.

**Запрос**:

- Пустое сообщение

**Ответ**:

- `session_status` (SessionStatus): Текущий статус сессии:
  - `ACTIVE` - сессия активна и готова к работе
  - `NOT_INITIALIZED` - сессия не была инициализирована
  - `EXPIRED` - сессия истекла
- `remaining_seconds` (uint32): Оставшееся время сессии в секундах (только если `session_status == ACTIVE`)
- `error_message` (string): Описание ошибки, если статус не ACTIVE

**Поведение**:

- Может быть вызван в любой момент
- Не требует инициализированной сессии
- Используется для проверки доступности сервиса

### Формат зашифрованных данных

Зашифрованные данные имеют следующий формат:

```
[IV: 16 bytes][Encrypted Data: variable length]
```

- **IV (Initialization Vector)**: 16 байт случайных данных, генерируемых для каждого шифрования
- **Encrypted Data**: Зашифрованные данные переменной длины

При дешифровании первые 16 байт извлекаются как IV, остальные - как зашифрованные данные.

### Алгоритм шифрования

- **Алгоритм**: AES-256
- **Режим**: CBC (Cipher Block Chaining) или GCM (Galois/Counter Mode)
- **Размер ключа**: 256 бит (32 байта)
- **Размер блока**: 128 бит (16 байт)
- **IV**: 16 байт, генерируется случайно для каждого шифрования

### Жизненный цикл сессии

1. **Запуск процесса**: aes-agent запускается как отдельный процесс
2. **Инициализация**: Клиент вызывает `Initialize` с master key
3. **Активная работа**: Клиент может вызывать `Encrypt` и `Decrypt`
4. **Таймаут**: Через 15 минут (или указанное время) сессия автоматически истекает
5. **Завершение**: При истечении таймаута или завершении процесса ключ очищается из памяти

### Обработка ошибок

Все методы возвращают статус операции. В случае ошибки:

- `status` содержит код ошибки
- `error_message` содержит человекочитаемое описание
- Данные не возвращаются (пустые поля)

### Безопасность

- Ключ никогда не передается после инициализации
- Ключ хранится только в памяти процесса aes-agent
- Ключ не логируется и не попадает в исключения
- При завершении процесса ключ должен быть очищен из памяти
- Каждое шифрование использует уникальный IV

**Файл**: `project/Settings.scala`

- Добавить версии для gRPC зависимостей (scalapb, grpc-netty, grpc-services)
- Учесть совместимость с GraalVM native image

**Файл**: `project/plugins.sbt`

- Добавить плагин `sbt-protoc` и `ScalaPB` для компиляции proto файлов

### 2. Создание подпроекта aes-agent

**Файл**: `build.sbt`

- Создать подпроект `aesAgent`:
  - Зависимость от `aesAgentProto` для использования сгенерированного кода
  - Включить плагин `NativeImagePlugin` для компиляции в native image
  - Настроить main class для aes-agent
  - Расположение: `aesAgent/src/main/scala/`

**Новый файл**: `aesAgent/src/main/scala/aesagent/AesAgentApp.scala`

- Главный класс приложения, расширяющий `IOApp`
- Инициализация gRPC сервера
- Обработка входящих запросов
- Управление ключом в памяти
- Реализация таймаута сессии (15 минут)

**Новый файл**: `aesAgent/src/main/scala/aesagent/AesAgentService.scala`

- Реализация gRPC сервиса `AesAgentService`
- Методы: `initialize`, `encrypt`, `decrypt`, `healthCheck`
- Использование AES-256 для шифрования/дешифрования

**Новый файл**: `aesAgent/src/main/scala/aesagent/SessionManager.scala`

- Управление сессией с ключом
- Таймер для отслеживания таймаута
- Безопасное хранение ключа в памяти

### 3. Обновление основного проекта alpasso

**Файл**: `build.sbt`

- Добавить зависимость `alpasso` от `aesAgentProto`:
  - `dependsOn(aesAgentProto)`
- Добавить gRPC зависимости для клиента (grpc-netty, grpc-services)

### 4. Расширение CypherAlg enum

**Файл**: `alpasso/src/main/scala/alpasso/infrastructure/cypher/CypherAlg.scala`

- Добавить новый вариант `MasterKey` в enum `CypherAlg`
- Обновить `Show`, `Encoder`, `Decoder` для поддержки нового варианта
- Структура: `case MasterKey(sessionTimeout: FiniteDuration)` или просто `case MasterKey` с фиксированным таймаутом

### 5. Реализация CypherService для Master Key

**Файл**: `alpasso/src/main/scala/alpasso/infrastructure/cypher/CypherService.scala`

- Создать `MasterKeyImpl[F[_]]` класс, реализующий `CypherService[F]`
- Использовать сгенерированные классы из `aesAgentProto` для gRPC клиента
- Реализовать управление процессом aes-agent:
  - Запуск процесса `aesAgent/nativeImage` при создании сервиса
  - Подключение к gRPC серверу (локальный порт или unix socket)
  - Инициализация сессии с ключом (ввод через stdin/TTY)
  - Таймаут сессии (15 минут)
  - Автоматическое завершение процесса при истечении таймаута
- Реализовать методы `encrypt` и `decrypt` через gRPC вызовы
- Обработка ошибок: `CypherErr.InvalidCypher`, `CypherErr.SessionExpired`, `CypherErr.AgentError`

### 6. Управление сессией и процессом

**Новый файл**: `alpasso/src/main/scala/alpasso/infrastructure/cypher/AesAgentManager.scala`

- Трейт `AesAgentManager[F[_]]` для управления жизненным циклом агента:
  - `start(key: Array[Byte]): F[Either[AesAgentErr, AesAgentClient]]`
  - `stop(): F[Unit]`
  - `isAlive: F[Boolean]`
- Реализация с использованием `cats-effect` `Resource` для управления процессом
- Использование `ProcessBuilder` для запуска внешнего процесса `aesAgent/nativeImage`
- Определение пути к бинарнику aes-agent (из подпроекта или системный PATH)
- Управление gRPC каналом и клиентом (используя классы из `aesAgentProto`)
- Таймер для отслеживания таймаута сессии

### 7. Ввод ключа

**Новый файл**: `alpasso/src/main/scala/alpasso/infrastructure/cypher/KeyInput.scala`

- Функция для безопасного ввода ключа:
  - Использование `cats-effect.std.Console` для чтения из stdin
  - Поддержка скрытого ввода (masked input) для безопасности
  - Валидация ключа (32 байта для AES-256)
  - Возможность ввода в hex формате или raw bytes

### 8. Обновление CypherProvisioner

**Файл**: `alpasso/src/main/scala/alpasso/infrastructure/filesystem/SessionProvisioner.scala`

- Обновить `CypherProvisioner` для поддержки `CypherAlg.MasterKey`:
  - При инициализации запрашивать ключ у пользователя
  - Создавать `CypherService.masterKey` вместо только `gpg`
  - Проверять работоспособность через тестовое шифрование

### 9. Обновление Command.make

**Файл**: `alpasso/src/main/scala/alpasso/commands/commands.scala`

- Добавить обработку `CypherAlg.MasterKey` в `Command.make`:
  - Создавать `CypherService.masterKey` для нового алгоритма
  - Управлять жизненным циклом сессии

### 10. Обновление ArgParser

**Файл**: `alpasso/src/main/scala/alpasso/cmdline/ArgParser.scala`

- Добавить опцию `--master-key` в команду `repo init`:
  - `Opts.flag("master-key", "Use master key encryption").map(_ => CypherAlg.MasterKey)`

### 11. Расширение ошибок

**Файл**: `alpasso/src/main/scala/alpasso/infrastructure/cypher/CypherService.scala`

- Расширить `CypherErr` enum:
  - `SessionExpired` - сессия истекла
  - `AgentError(message: String)` - ошибка агента
  - `KeyInputError` - ошибка ввода ключа

### 12. Интеграция с SessionManager (опционально)

**Файл**: `alpasso/src/main/scala/alpasso/infrastructure/session/SessionManager.scala`

- Возможно, добавить информацию о типе шифрования в сессию
- Или хранить состояние активной сессии master-key отдельно

## Порядок реализации

1. **Настройка подпроектов**:

   - Добавить плагины для proto компиляции в `project/plugins.sbt`
   - Создать подпроект `aesAgentProto` в `build.sbt`
   - Создать подпроект `aesAgent` в `build.sbt`
   - Добавить зависимости между проектами

2. **Proto файлы**:

   - Создать `aesAgentProto/src/main/proto/aes_agent.proto`
   - Настроить генерацию Scala кода из proto
   - Проверить, что код генерируется корректно

3. **Реализация aes-agent**:

   - Создать `AesAgentApp` - главный класс
   - Реализовать gRPC сервер
   - Реализовать `AesAgentService` с методами шифрования/дешифрования
   - Реализовать `SessionManager` для управления ключом и таймаутом
   - Настроить native image компиляцию для aes-agent

4. **Интеграция в alpasso**:

   - Расширить `CypherAlg` enum и сериализацию
   - Реализовать `KeyInput` для безопасного ввода ключа
   - Создать `AesAgentManager` для управления процессом
   - Реализовать `MasterKeyImpl` в `CypherService`
   - Обновить `CypherProvisioner` и `Command.make`
   - Обновить `ArgParser` для поддержки нового алгоритма
   - Добавить обработку ошибок

## Структура файлов после реализации

```
alpasso/
├── build.sbt                          # Обновлен: добавлены подпроекты
├── project/
│   ├── plugins.sbt                    # Обновлен: добавлены proto плагины
│   └── Settings.scala                 # Обновлен: добавлены gRPC зависимости
├── aesAgentProto/                     # Новый подпроект
│   └── src/
│       └── main/
│           └── proto/
│               └── aes_agent.proto    # Новый файл
├── aesAgent/                          # Новый подпроект
│   └── src/
│       └── main/
│           └── scala/
│               └── aesagent/
│                   ├── AesAgentApp.scala
│                   ├── AesAgentService.scala
│                   └── SessionManager.scala
└── alpasso/                           # Существующий проект
    └── src/
        └── main/
            └── scala/
                └── alpasso/
                    └── infrastructure/
                        └── cypher/
                            ├── CypherAlg.scala          # Обновлен
                            ├── CypherService.scala       # Обновлен
                            ├── AesAgentManager.scala     # Новый
                            └── KeyInput.scala            # Новый
```

## Важные моменты

- **GraalVM Native Image**: 
  - Убедиться, что gRPC работает с native image (может потребоваться дополнительная конфигурация)
  - Настроить native image для обоих проектов: `alpasso` и `aesAgent`
  - Возможно потребуется добавить reflection конфигурацию для gRPC

- **Безопасность**: 
  - Ключ никогда не должен логироваться или попадать в исключения
  - Ключ должен храниться только в памяти процесса aes-agent
  - При завершении процесса ключ должен быть очищен из памяти

- **Таймаут**: 
  - Сессия должна автоматически завершаться через 15 минут неактивности или по таймеру
  - При истечении таймаута aes-agent должен завершиться

- **Процесс**: 
  - aes-agent должен корректно завершаться при завершении работы alpasso
  - Нужно определить способ коммуникации (порт, unix socket, или другой)
  - Путь к бинарнику aes-agent должен быть настраиваемым

- **Ошибки**: 
  - Все ошибки должны быть обработаны и преобразованы в `CypherErr`
  - Ошибки gRPC должны быть корректно обработаны

- **Зависимости между проектами**:
  - `aesAgentProto` не зависит ни от чего
  - `aesAgent` зависит от `aesAgentProto`
  - `alpasso` зависит от `aesAgentProto` (для gRPC клиента)
