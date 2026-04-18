# 🐛 "컴팩트 소스 파일은 언어 수준 '17'에서 지원되지 않습니다" 오류 해결

## 🚨 발생한 오류

```
콤팩트 소스 파일은(는) 언어 수준 '17'에서 지원되지 않습니다
```

## 🔍 원인

`GlobalExceptionHandler.java` 파일이 **클래스 선언 없이** 메서드만 있는 잘못된 구조였습니다:

### ❌ 잘못된 코드 (컴팩트 소스 파일)
```java
import com.ReadMe.demo.dto.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ExceptionHandler(FolderNotEmptyException.class)
public ResponseEntity<ApiErrorResponse> handleFolderNotEmpty(...) {
    // 클래스 없이 메서드만 있음!
}
```

이런 형태는:
- **Java 11의 미리보기 기능** (Single-File Source-Code Programs)
- **Java 17에서는 지원되지 않음**
- Spring에서 예외 핸들러로 인식되지 않음

---

## ✅ 해결 방법

### 1. GlobalExceptionHandler를 올바른 클래스로 수정

```java
package com.ReadMe.demo.exception;

import com.ReadMe.demo.dto.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice  // ← 이게 있어야 Spring이 인식!
public class GlobalExceptionHandler {  // ← 클래스 선언 필수!

    @ExceptionHandler(FolderNotEmptyException.class)
    public ResponseEntity<ApiErrorResponse> handleFolderNotEmpty(FolderNotEmptyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(
                        "FOLDER_NOT_EMPTY",
                        "폴더 안에 파일 또는 하위 폴더가 있습니다.",
                        e.getInfo()
                ));
    }
    
    @ExceptionHandler(FileNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleFileNotFound(FileNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(
                        "FILE_NOT_FOUND",
                        e.getMessage(),
                        null
                ));
    }
    
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of(
                        "INVALID_ARGUMENT",
                        e.getMessage(),
                        null
                ));
    }
    
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> handleRuntimeException(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(
                        "INTERNAL_ERROR",
                        e.getMessage(),
                        null
                ));
    }
}
```

### 2. FileNotFoundException 클래스 생성

빈 파일이었던 `FileNotFoundException.java`를 제대로 구현:

```java
package com.ReadMe.demo.exception;

public class FileNotFoundException extends RuntimeException {
    
    public FileNotFoundException(String message) {
        super(message);
    }
    
    public FileNotFoundException(Long fileId) {
        super("파일을 찾을 수 없습니다: ID = " + fileId);
    }
}
```

---

## 🎯 올바른 Java 파일 구조

### ✅ 표준 Java 클래스 (Java 8+)
```java
package com.example;

import ...;

public class MyClass {
    // 필드
    // 생성자
    // 메서드
}
```

### ❌ 컴팩트 소스 (Java 11 미리보기, 단일 실행 전용)
```java
// 패키지 선언 없음
import ...;

public void main() {
    // 메서드만 있음
}
```

---

## 📋 체크리스트

- [x] `GlobalExceptionHandler`에 `@RestControllerAdvice` 추가
- [x] `GlobalExceptionHandler`를 `public class`로 감싸기
- [x] `FileNotFoundException` 클래스 구현
- [x] 모든 import 문 확인
- [x] package 선언 확인

---

## 🔧 Java 버전 설정 확인

### build.gradle
```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
```

### IntelliJ 설정
1. **File → Project Structure → Project**
   - Project SDK: 17
   - Language level: 17

2. **File → Settings → Build, Execution, Deployment → Compiler → Java Compiler**
   - Project bytecode version: 17

---

## 🚀 테스트

이제 다음 명령어로 빌드가 성공해야 합니다:

```bash
./gradlew clean build
./gradlew bootRun
```

---

## 💡 추가 팁

### 예외 핸들러 사용법

```java
// Service나 Controller에서
throw new FileNotFoundException(fileId);
throw new FolderNotEmptyException(info);
throw new IllegalArgumentException("잘못된 파라미터");
```

### API 응답 형식

```json
{
  "code": "FILE_NOT_FOUND",
  "message": "파일을 찾을 수 없습니다: ID = 123",
  "data": null
}
```

---

## 📝 수정된 파일

1. ✅ `GlobalExceptionHandler.java` - 클래스 구조로 수정
2. ✅ `FileNotFoundException.java` - 예외 클래스 구현

---

## 🎉 완료!

이제 컴파일 오류 없이 정상적으로 실행될 것입니다!
