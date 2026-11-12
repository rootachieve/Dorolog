# Android 개발 지침

## 화면·상태·데이터 구조

- API 데이터는 DTO로 수신하고 Retrofit을 사용한다. DTO와 화면 모델을 구분하고 변환·검증은 데이터 계층에서 수행한다.
- 비즈니스 화면은 화면(Composable), ViewModel, 모델로 구성한다. 기존 구조를 재사용하고 불필요한 계층·추상화·파일 분리를 추가하지 않는다.
- 각 ViewModel 내부에 해당 화면의 불변 `data class UiState`를 선언한다. `private MutableStateFlow`를 `asStateFlow()`로 캡슐화하여 읽기 전용 `uiState`를 노출한다.
- 화면 진입 Composable에서 `collectAsStateWithLifecycle()`로 상태를 수집하고 `Screen(uiState = uiState, …)`에 전달한다.
- 화면 데이터·선택·카운트·비즈니스 전환 상태의 변경은 반드시 ViewModel 메서드에서 수행한다. Composable은 이벤트 콜백을 전달하고, 애니메이션·스크롤 등 표시 전용 상태만 직접 관리한다.
- Composable은 사용하는 상태와 책임에 따라 분리한다. 재사용하지 않는 화면 구성 요소는 같은 화면 파일에 유지한다.
