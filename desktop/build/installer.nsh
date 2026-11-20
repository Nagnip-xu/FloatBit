; These hooks run BEFORE PAGE_INSTALL_MODE expands its leave callback.
; customHeader is too late: by then the crashing code is already compiled.
!macro customWelcomePage
  !include "${BUILD_RESOURCES_DIR}\perUserMode.nsh"
!macroend
!macro customUnWelcomePage
  !include "${BUILD_RESOURCES_DIR}\perUserMode.nsh"
  !insertmacro MUI_UNPAGE_WELCOME
!macroend
!macro customHeader
  !ifndef FLOATBIT_SAFE_PER_USER
    !error "FloatBit safe per-user installation macro was not installed before the mode page"
  !endif
!macroend
