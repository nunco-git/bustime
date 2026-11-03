# 버스 도착정보 (bustime)

국토교통부 TAGO 버스 API로 정류장별 도착 예정 시간을 보여주고,
지정한 버스가 가까워지면 음성으로 안내하는 앱입니다.

| 폴더 | 내용 |
|---|---|
| `public/` | 웹 화면 (Firebase Hosting) |
| `functions/` | 공공데이터 API 호출용 Firebase Function (인증키는 서버 비밀값으로만 보관) |
| `android/` | 안드로이드 앱 (웹 화면 + 백그라운드 음성 안내 서비스) |
| `.github/workflows/android.yml` | GitHub에서 APK를 자동으로 빌드 |

## APK 받는 법
1. 저장소의 **Actions** 탭 → **Build Android APK** → 가장 최근 실행을 엽니다.
2. 맨 아래 **Artifacts**의 `bustime-debug-apk`를 내려받아 압축을 풀면 `app-debug.apk`가 나옵니다.
3. 폰으로 옮겨 설치합니다. ("출처를 알 수 없는 앱 설치" 허용 필요)

수동으로 다시 빌드하려면 Actions → Build Android APK → **Run workflow**.

## 웹 배포
```
firebase deploy --project bustime-c4cfd
```
인증키는 코드에 넣지 않고 `firebase functions:secrets:set TAGO_KEY`로 등록합니다.

## 계정 (여러 기기에서 같은 설정)
- 설정(⚙) → 이메일·비밀번호로 **가입** 또는 **로그인**합니다. 어르신 폰과 자녀 폰에서 같은 계정으로 로그인하면 됩니다.
- 버스 번호, 몇 분 전, 음성 안내 켜기/끄기, 안내 정류장이 계정에 저장되어 모든 기기에 바로 반영됩니다.
- 여러 기기에서 동시에 로그인할 수 있습니다. 자녀 폰에서 소리가 나지 않게 하려면 그 기기에서 "이 기기에서 음성 안내 듣기"를 끄세요.

### Firebase 콘솔에서 한 번만 해 두기
1. Authentication → 시작하기 → 로그인 방법에서 **이메일/비밀번호** 사용 설정
2. Firestore Database → 데이터베이스 만들기 (위치: `asia-northeast3` 서울)
3. 배포: `firebase deploy --only firestore:rules,hosting --project bustime-c4cfd`

보안 규칙은 `firestore.rules` 참고 (설정은 해당 계정으로 로그인한 사람만 읽고 고칠 수 있음).
