# 이야기 보관함 서버 연결

서버의 `server/API_CONTRACT.md`를 사용한다. 서버 주소가 없거나 조회가 실패하면 오류와 재시도를 표시하며, 샘플로 대체하지 않는다. 샘플 여행 13개와 저장 이야기 51개는 명시적으로 주입하는 테스트·Compose 미리보기 전용이다.

## 여행별 탭

`GET {baseUrl}trips?limit=50`

```json
{
  "trips": [{
    "id": "trip-id",
    "originName": "서울",
    "originLatitude": 37.5665,
    "originLongitude": 126.978,
    "destinationName": "부산",
    "destinationLatitude": 35.1796,
    "destinationLongitude": 129.0756,
    "status": "finished",
    "startedAt": "2026-11-14T05:30:00+00:00",
    "endedAt": "2026-11-14T08:10:00+00:00",
    "storyCount": 4
  }]
}
```

- 최근 여행 최대 50개를 표시한다. 최신순·오래된순은 수신한 여행의 `startedAt`을 앱에서 정렬하며, 정렬할 때 서버를 다시 조회하지 않는다.
- 목적지 이름과 좌표는 없을 수 있다. 좌표는 위도·경도가 함께 값이 있거나 함께 `null`이어야 한다. 좌표가 없는 카드에는 위치 정보가 없다는 안내를 표시하며 임의 좌표를 만들지 않는다.
- ID는 공백이 아닌 문자열로 검증한 뒤 그대로 상세 조회에 전달한다.
- `title`, `departureName`, `destinations`, `date`, `regionId` 같은 별도 서버 필드를 요구하지 않는다. 화면의 출발지는 `originName`, 날짜는 `startedAt`의 기기 현지 날짜를 사용한다.

## 전국 이야기 지도

`GET {baseUrl}stories/map`

```json
{
  "storyCount": 1,
  "tripCount": 1,
  "regions": [{"region": "천안", "storyCount": 1, "latitude": 36.8151, "longitude": 127.1139}],
  "stories": [{
    "id": "story-id", "tripId": "trip-id", "title": "가족 여행의 추억", "region": "천안",
    "latitude": 36.8151, "longitude": 127.1139,
    "summary": "가족과 함께했던 여행 이야기를 나눴다.",
    "highlights": ["그때 즐거웠어"], "recordedAt": "2026-11-14T14:30:00+09:00"
  }]
}
```

- 저장 확정된 전체 이야기와 전체 통계를 사용한다. 지도 숫자와 지역 선택 메뉴는 `regions[].storyCount`이며 여행 목적지나 최근 50개 여행 수에서 추정하지 않는다.
- 좌표가 없는 지역은 지도에 마커를 만들지 않는다. 지역 선택 메뉴와 이야기 목록은 사용할 수 있다.
- 지도 첫 화면에는 전체 이야기의 제목·지역·기록 날짜 목록을 표시한다. 이야기 행을 누르면 해당 지역을 선택하며, 지도 마커나 지역 선택 메뉴로도 같은 지역을 선택할 수 있다.
- 지역을 고르면 해당 이야기의 `region`으로 필터링하고, 오른쪽 패널에 제목·지역·기록 시간·`summary`·`highlights`를 카드로 표시한다. 한 지역에 이야기가 여러 개면 목록을 스크롤해 확인한다. `목록으로`를 누르면 전체 이야기 목록으로 돌아간다.
- 핵심 대화는 공백을 제외한 `highlights`를 말풍선으로 표시한다. 말풍선은 해당 지역을 선택한 동안 각각 화면에 처음 들어올 때 순서대로 나타나며, 스크롤로 다시 보아도 재생하지 않는다. 애니메이션 설정이 꺼지면 즉시 표시하고 백그라운드에서는 진행하지 않는다.
- 기록 시간이 없는 이야기도 유지한다. 날짜는 정보 없음으로 표시하며 정렬에서는 마지막에 둔다. 이야기 식별자는 `(tripId, id)` 쌍이다.
- 지도에서 사용하는 본문·핵심 대화는 기존 `stories/map` 응답에 포함되어 있으므로 추가 상세 조회는 하지 않는다. 최근 50개 여행 목록에 없는 여행의 이야기도 표시한다.

## 빌드와 지도 설정

```sh
./gradlew :app:assembleDebug -Pdorolog.apiBaseUrl=https://example.com/api/
```

기본 주소는 호스트가 있는 HTTPS URL이고 `/api/`로 끝나야 한다. 사용자 이름·비밀번호·쿼리·fragment는 허용하지 않는다. `BuildConfig.TRIP_API_BASE_URL`로 전달된다.

카카오 지도 키는 Git에서 제외된 `Android/local.properties`의 `dorolog.kakaoMapNativeAppKey` 또는 같은 이름의 Gradle 속성으로 설정한다. Gradle 속성이 우선한다. 카카오 개발자 콘솔에는 `com.forever.dorolog` Android 플랫폼과 인증서 키 해시를 등록해야 한다. 키가 없어도 지역 메뉴와 저장 이야기 목록은 사용할 수 있다.

여행 카드 지도는 하나씩 캡처하여 최대 12MB 메모리 캐시에 보관한다. SDK의 타일 완료 콜백이 없어 지도 준비 후 1.5초 뒤 캡처하므로 느린 네트워크의 지도 배경은 키 등록 후 실행 검증이 필요하다.

## 상세 화면과 상태

`ArchiveRoute.onTripClick`은 `(tripId: String, destinationName: String?) -> Unit`이며 여행별 탭의 카드에서 결산 화면을 연다. 결산 화면은 `GET trips/{tripId}`에서 실제 정보를 조회한다. 확인은 홈으로, 뒤로가기는 보관함으로 돌아간다. 지도 탭은 처음에 전체 지도를 표시하고, 지역 마커나 지역 메뉴를 선택하면 해당 지역의 이야기 본문·핵심 대화 목록을 바로 표시한다. ‘지도로’와 시스템 Back은 전체 지도로 돌아간다. 이야기 번호는 정렬 방향에 관계없이 최신 이야기가 더 큰 번호를 갖는다.

`ArchiveBrowser`는 상세 화면에서 돌아올 때 같은 ViewModel과 표시 상태를 유지한다. 탭·정렬·지역 선택·조회 결과·스크롤 위치를 복원하며, 홈으로 돌아가면 이 메모리 상태를 정리한다. 앱 재시작 시 보관함이나 여행으로 자동 복귀하지 않는다.

보관함은 주행 규제 상태에서 탐색을 숨기고 홈 동작을 유지한다. Pleos Connect 에뮬레이터·Virtual Console의 차량 상태 변경에 따른 반응과 지도 인증·타일은 실행 환경에서 검증한다.
