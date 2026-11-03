# 버스 도착정보 (bustime)

국토교통부 TAGO 버스 API로 정류장별 도착 예정 시간을 보여주고,
지정한 버스가 가까워지면 음성으로 안내하는 앱입니다.

## 📲 앱(APK) 내려받기
**[➡ bustime.apk 바로 받기](https://github.com/nunco-git/bustime/releases/download/latest/bustime.apk)** ·
[릴리스 페이지](https://github.com/nunco-git/bustime/releases/tag/latest)

1. 폰에서 위 링크를 눌러 `bustime.apk`를 받습니다.
2. 받은 파일을 눌러 설치합니다. ("출처를 알 수 없는 앱 설치" 허용이 필요할 수 있습니다)
3. 기존 앱 위에 덮어 설치해도 로그인과 설정은 그대로 남습니다.
4. 안드로이드 5.0 이상에서 설치됩니다. 7.0 미만 폰은 가벼운 "간단 화면"이 자동으로 열립니다.
5. 설치 후 설정에서 배터리 사용을 "제한 없음"으로 바꾸면 화면이 꺼져도 음성 안내가 안정적으로 나옵니다.

웹으로도 쓸 수 있습니다: https://bustime-c4cfd.web.app

| 폴더 | 내용 |
|---|---|
| `public/` | 웹 화면 (Firebase Hosting) |
| `functions/` | 공공데이터 API 호출용 Firebase Function (인증키는 서버 비밀값으로만 보관) |
| `android/` | 안드로이드 앱 (웹 화면 + 백그라운드 음성 안내 서비스) |
| `.github/workflows/android.yml` | GitHub에서 APK를 자동으로 빌드 |

## APK 빌드
`android/` 폴더를 고쳐 main에 올리면 GitHub Actions가 자동으로 APK를 빌드해 Releases(`latest`)에 올립니다.
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
