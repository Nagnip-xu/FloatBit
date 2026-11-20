  !ifndef INSTALL_MODE_PER_ALL_USERS
    !define FLOATBIT_SAFE_PER_USER
    !macroundef setInstallModePerUser
    !macro setInstallModePerUser
      StrCpy $installMode CurrentUser
      SetShellVarContext current
      ReadRegStr $perUserInstallationFolder HKCU "${INSTALL_REGISTRY_KEY}" InstallLocation
      ${If} $perUserInstallationFolder != ""
        StrCpy $INSTDIR $perUserInstallationFolder
      ${Else}
        StrCpy $INSTDIR "$LOCALAPPDATA\Programs\${APP_FILENAME}"
      ${EndIf}
      ${StdUtils.GetParameter} $R0 "D" ""
      ${If} $R0 != ""
        StrCpy $INSTDIR $R0
      ${EndIf}
    !macroend
  !endif
