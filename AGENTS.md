# Android 개발 지침

- 개발 계획을 수립하기 전과 개발 후 검증할 때에는 반드시 [Pleos Connect 앱 기획·설계 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/intro)를 확인하고, 해당 작업에 적용되는 지침을 반영한다.
- 이 앱은 Pleos Connect Emulator를 사용하는 프로젝트다.
- [Pleos Connect Virtual Console 셋업 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)에 따라 에뮬레이터를 실행하고 `adb forward tcp:8088 tcp:8088`로 연결한 뒤, 컴퓨터 유즈로 브라우저의 `http://127.0.0.1:8088/`에서 차량 상태와 이벤트를 조작하며 테스트할 수 있다. 변경에 따른 에뮬레이터와 앱의 반응을 확인한다. Virtual Console은 에뮬레이터 v2.1.0 이상을 지원한다.
