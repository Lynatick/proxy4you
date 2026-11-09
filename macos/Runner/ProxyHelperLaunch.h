#ifndef PROXY4YOU_HELPER_LAUNCH_H
#define PROXY4YOU_HELPER_LAUNCH_H

#include <Security/Authorization.h>
#include <stdio.h>

// Local ad hoc builds cannot install a Developer-ID-signed ServiceManagement helper.
// Keep the legacy launch confined to one fixed bundled tool, with no tool arguments.
static inline OSStatus Proxy4YouLaunchSessionHelper(
    AuthorizationRef _Nonnull authorization, const char * _Nonnull path,
    FILE * _Nullable * _Nonnull communications) {
  char *arguments[] = {NULL};
  return AuthorizationExecuteWithPrivileges(authorization, path,
                                           kAuthorizationFlagDefaults,
                                           arguments, communications);
}

#endif
