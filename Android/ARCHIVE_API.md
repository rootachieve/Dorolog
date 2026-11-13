# 이야기 지도 서버 연결

이 문서는 통합된 Android 이야기 지도 클라이언트의 요청·응답 계약이다. 서버 구현은 이 저장소의 Android 변경에 포함하지 않는다.

서버 주소 미설정 시 화면에는 `isSample = true`인 샘플 여행 13개와 이야기 51개를 공급한다. 화면의 샘플 표시를 유지해야 한다. 서버 주소를 설정하면 실제 요청만 사용하며, 실패해도 샘플 데이터로 바꾸지 않는다.

## 여행 목록

`GET {baseUrl}trips?sort=latest` 또는 `GET {baseUrl}trips?sort=oldest`

- `latest`: 여행 날짜 내림차순, 같은 날짜는 서버가 일관된 순서를 보장한다.
- `oldest`: 여행 날짜 오름차순, 같은 날짜는 서버가 일관된 순서를 보장한다.
- 응답은 해당 사용자의 **완료된 여행 전체**다. 현 계약에는 페이지네이션이 없다. 페이지네이션 도입 시 전체 통계·지역 집계 API를 함께 추가해야 한다.
- 서버에서 현재 로그인한 사용자의 여행만 반환해야 한다. 현재 클라이언트에는 인증 헤더가 없으므로, 인증이 필요한 서버에 연결할 때는 서비스의 인증 흐름을 Retrofit 클라이언트에 연결한다.

```json
{
  "trips": [
    {
      "id": "trip-20261111",
      "title": "바다를 보며 나눈 이야기",
      "departureName": "광주 용봉동",
      "destinations": [
        {
          "name": "전주 한옥마을",
          "latitude": 35.8144,
          "longitude": 127.1526,
          "regionId": "45110",
          "regionName": "전주시"
        },
        {
          "name": "부산 해운대",
          "latitude": 35.1587,
          "longitude": 129.1604,
          "regionId": "26000",
          "regionName": "부산"
        }
      ],
      "date": "2026-11-11",
      "storyCount": 5
    }
  ]
}
```

- `id`는 공백으로만 구성되지 않은 문자열이다. 앱은 별도 형식이나 길이를 강제하거나 ID를 재작성하지 않고 결산 화면에 그대로 전달한다. 음성 세션 ID와 구분한다.
- `departureName`은 여행 출발지다. `destinations`는 방문 순서대로 제공하며, **마지막 목적지**의 이름·좌표·지역만 카드와 지도에 사용한다. 이야기 발생 위치나 중간 경유지로 대체하지 않는다.
- `regionId`는 서로 다른 도의 동명 시·군을 구분할 수 있는 안정적인 시·군 단위 코드다. `regionName`은 사용자에게 보여줄 지역명이며, 같은 코드에는 같은 이름을 제공한다.
- 좌표는 WGS84 위도·경도다. 카드 배경은 최종 목적지 좌표를 사용한다. 지역 마커는 동일 시·군 여행의 최종 목적지 좌표 평균에 표시하고 숫자는 여행 수다.
- `date`는 서버가 여행 날짜로 정한 유효한 `YYYY-MM-DD`다. 클라이언트는 시간대를 재해석하지 않고 해당 날짜를 표시한다.
- `storyCount`는 해당 여행에서 **저장한 이야기 개수**인 0 이상의 정수다. 결산에서 사용하는 서버의 최종 `savedStoryIds` 개수와 일치해야 하며, 저장하지 않은 대화는 포함하지 않는다. 저장한 이야기가 없으면 `0`이다. 이야기 합계는 모든 여행의 `storyCount` 합이며, 여행 합계는 여행 수다.
- `title`을 생략하거나 비우면 `출발지 → 최종 목적지`를 제목으로 사용한다. 그 외 위 필드는 필수다. 빈 목록은 `{"trips": []}`로 반환한다.

## 빌드 설정

결산 화면 워크트리와 동일한 속성을 사용한다.

```sh
./gradlew :app:assembleDebug -Pdorolog.apiBaseUrl=https://example.com/api/
```

주소는 호스트가 있는 HTTPS URL이며 끝에 `/`가 있어야 한다. 사용자 이름·비밀번호, 쿼리 문자열, fragment는 허용하지 않는다. `BuildConfig.TRIP_API_BASE_URL`로 전달된다.

카카오 지도 네이티브 앱 키는 Git에서 제외된 `Android/local.properties`에 저장한다. 아래 `native_key`는 자리표시자이며 발급받은 실제 키로 바꾼다.

```properties
dorolog.kakaoMapNativeAppKey=native_key
```

빌드 시 이 값이 `BuildConfig.KAKAO_MAP_NATIVE_APP_KEY`로 전달되어 지도 SDK 초기화에 사용된다. 필요하면 `-Pdorolog.kakaoMapNativeAppKey=native_key`로 덮어쓸 수 있으며, Gradle 속성 값이 `local.properties`보다 우선한다. 실제 키와 `local.properties`를 Git에 추가하거나 커밋하지 않는다.

카카오 개발자 콘솔에는 `com.forever.dorolog` Android 플랫폼과 빌드 인증서의 키 해시를 등록해야 한다. 키 미설정 시 SDK를 시작하지 않고 안내를 표시한다. 지역 선택 메뉴로 키 없이도 샘플 목록 필터를 확인할 수 있다.

여행 카드 지도는 GL 지도 여러 개를 상주시키지 않도록 하나씩 캡처해 최대 12MB 메모리 캐시에 보관한다. SDK에 타일 완료 콜백이 없어 준비 후 1.5초 대기한 캡처를 사용하며, 느린 네트워크에서 지도 배경이 완전히 준비되는지 카카오 키 등록 후 검증해야 한다.

## 결산 화면과 메모리 상태

여행 카드와 지도 우측 목록은 `ArchiveRoute`의 `onTripClick: (ArchiveTrip) -> Unit`으로 선택한 여행을 전달한다. 상위 화면은 실제 결산 화면에 `trip.id`와 `trip.destinationName`을 전달한다. 결산의 확인 버튼(`onDone`)은 홈으로 이동하고, 뒤로가기(`onBack`)는 이야기 지도로 돌아간다.

```kotlin
TripSummaryScreen(
    tripId = trip.id,
    destinationName = trip.destinationName,
    onDone = { /* 홈 이동 및 보관함 메모리 상태 정리 */ },
    onBack = { /* 이야기 지도 복귀 */ },
)
```

결산 연결 대기용 화면은 사용하지 않는다. 샘플 ID를 운영 서버에 존재하는 여행 ID로 간주하지 않으며, 샘플임을 표시한 목록의 `isSample` 값도 상위 화면에서 확인할 수 있다.

`ArchiveViewModel`은 `SavedStateHandle`이나 디스크 저장소를 사용하지 않는다. 상위 `ArchiveBrowser`가 결산을 보는 동안 같은 ViewModel과 표시 상태 보관자를 유지한다. 뒤로가기로 이야기 지도에 돌아오면 탭·정렬·지역 선택·조회 결과·스크롤 위치를 그대로 사용한다. 확인 버튼으로 홈에 돌아가면 이 메모리 상태를 정리한다. 앱을 다시 시작하면 홈에서 시작하며 보관함·여행으로 자동 복귀하지 않는다.

## 검증 제약

지도 인증·타일·차량 상태 반응은 카카오 키와 대상 차량 환경을 설정한 후 실행 검증해야 한다. 실제 목록·결산 왕복의 서버 조회 검증에는 위 계약을 구현한 서버가 필요하다. 다른 워크트리의 서버를 이 화면의 서버로 사용하지 않는다.
