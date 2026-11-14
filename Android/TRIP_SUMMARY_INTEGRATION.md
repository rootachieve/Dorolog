# 결산 화면 연결

결산 진입점은 `TripSummaryScreen(tripId, onDone, modifier)`다. 홈에서 선택한 여행 ID를 그대로 전달하며 새로운 ID를 만들거나 로컬 여행을 복구하지 않는다. 결산 화면 파일·디자인 자산·테스트만 가져왔으며 서버 구현은 포함하지 않는다.

```kotlin
TripSummaryScreen(
    tripId = uiState.tripId,
    onDone = onHome,
    modifier = modifier,
    destinationName = uiState.destination?.name,
    animationsEnabled = animationsEnabled,
    browsingAllowed = browsingAllowed,
)
```

`onDone`은 확인 버튼에서 홈으로 이동하는 콜백이다. 여행 보관함에서 결산을 열 때는 `onBack = onArchive`를 추가한다. 이때 화면에 터치 가능한 뒤로 버튼이 나타나고 시스템 Back도 보관함으로 돌아간다. `onBack`이 없으면 시스템 Back은 `onDone`을 호출한다. 홈·보관함 이동 상태는 호출 측에서 관리한다.

기본 ViewModel은 결산 화면의 `ViewModelStore`에 속하며 화면을 나가면 해제된다. 같은 여행에 재진입하거나 다른 여행을 열면 새로 조회하고 이전 선택 말풍선을 다시 표시하지 않는다. 화면은 주입한 ViewModel의 여행 ID도 검증한다.

`animationsEnabled`에는 시스템 애니메이션 설정·포그라운드 상태·차량 주행 규제를 반영한 값을 전달한다. 대화 애니메이션은 이 값이 꺼지면 즉시 최종 상태를 표시하고 백그라운드에서는 진행하지 않는다. 원본의 가로 이야기 레일, 선택 시 상세 패널과 말풍선 순차 표시, 화면 밖으로 스크롤한 선택 해제 동작을 유지한다.

`browsingAllowed`에는 현재 차량의 주행 중 UI 제한 상태를 반영한다. 제한 중에는 ViewModel 생성·이야기 조회를 하지 않고 이야기 개수·레일·상세·말풍선을 모두 숨긴다. 대신 “안전한 곳에 정차한 후 여행 결산을 확인해 주세요” 안내와 홈 버튼, 제공된 경우 뒤로 버튼만 표시한다. 이미 결산을 보고 있던 중 제한되면 화면 소유 ViewModel도 해제하며 다시 허용되면 새로 조회한다. 주행 제한은 애니메이션 활성 여부와 별개로 적용해야 한다.

## 조회와 이야기 메타데이터

빌드 시 기존 `-Pdorolog.apiBaseUrl=https://host/api/`를 지정한다. 서버가 제공하는 다음 여행 단위 응답을 사용한다. 주소 미설정·HTTP 실패·응답 검증 실패는 오류와 재시도로 표시하며 샘플 이야기를 표시하지 않는다.

`GET trips/{tripId}`

```json
{
  "id": "chosen-trip-id",
  "originName": "서울",
  "originLatitude": 37.5665,
  "originLongitude": 126.978,
  "destinationName": "부산",
  "destinationLatitude": 35.1796,
  "destinationLongitude": 129.0756,
  "status": "finished",
  "startedAt": "2026-11-14T05:30:00+00:00",
  "endedAt": "2026-11-14T08:10:00+00:00",
  "storyCount": 1,
  "stories": [{
    "id": "story-1",
    "title": "다시 떠올린 가족 여행",
    "region": "서울",
    "latitude": 37.5665,
    "longitude": 126.978,
    "summary": "가족과 함께 지난 여행을 떠올렸어요.",
    "highlights": ["그때 기억나?", "응, 함께 갔던 곳이잖아."],
    "recordedAt": "2026-11-14T14:30:00+09:00"
  }],
  "savedStoryIds": ["story-1"]
}
```

응답은 여행 기본 정보에 `stories`, `savedStoryIds`가 더해진 평탄한 구조다. 응답 `id`는 요청한 비어 있지 않은 여행 ID와 정확히 같아야 한다. 이야기 ID는 여행 안에서 유일하며 `savedStoryIds`는 같은 응답의 이야기 ID만 중복 없이 포함해야 한다. 명시적인 `savedStoryIds`에 포함된 이야기만 표시하며 다른 저장 플래그나 구간별 대체 규칙을 사용하지 않는다. 이야기 키는 ID 자체다. 이야기 위도·경도는 함께 제공하거나 함께 null이어야 하며 유한한 값과 좌표 범위를 검증한다.

`recordedAt`은 ISO 8601 UTC/오프셋 시간 또는 null이다. 이 값을 화면 모델의 `occurredAt`으로 변환하며 null이면 화면에 “시간 정보 없음”을 표시한다. 시간 있는 이야기를 오름차순으로 정렬한 뒤 시간 없는 이야기를 표시하며 동일 시간과 시간 누락끼리는 서버 순서를 유지한다. 다른 필드나 현재 시각으로 대체하지 않는다. 화면 시간은 기기의 시간대로 표시한다.

본문 `summary`를 화면 모델의 `content`로, `highlights` 문자열 목록을 `conversationExcerpts`로 변환한다. 요약이 없거나 공백이면 준비되지 않았다는 안내를 표시한다. 핵심 대화 목록이 없거나 빈 배열이거나 공백 문자열뿐이면 핵심 대화가 준비되지 않았다는 안내를 표시한다. null 목록 원소는 잘못된 응답으로 처리하며 화면에는 공백을 제외하고 최대 3개를 보여준다. 이야기를 새로 만들어 채우지 않는다.

주차 화면의 최근 등록순 조회와 결산 화면의 시간순 표시는 각각의 목적에 따라 다르다. 결산은 서버가 생성·저장한 메타데이터를 조회해 표시한다. 이 통합은 Python 서버나 서버 마이그레이션을 가져오지 않는다.

## 디자인 자산과 검증

말풍선 꼬리는 원본 `Android/design/trip_summary_tail.svg`와 동일한 4배 PNG인 `drawable-nodpi/trip_summary_tail.png`를 사용한다. SVG는 39×41, PNG는 156×164이며 화면에서도 39:41 비율을 유지한다. 원본 SVG는 APK에 중복 포장하지 않는다.

`TripSummaryDataTest`, `TripSummaryViewModelTest`, `TripSummaryLayoutTest`와 `TripSummaryScreenTest`가 데이터 검증·정렬·재시도·선택·스크롤·뒤로가기·화면 재진입을 다룬다. 테스트 실행 결과는 메인 통합 검증에서 확인한다. 서버 성공이나 실차 검증 결과를 이 문서의 근거로 삼지 않는다.
