# Native IIS failed run, retained before retry

Run: https://github.com/AnonymousAlloyLab/AlloyStudio/actions/runs/37297857060
Revision: 3b91f1fb338e45cd96be4c9fad70b5eb06f199ac

Confirmed: native Windows build, PowerShell parser checks, native filesystem/path regressions, IIS feature installation, both Microsoft MSI hash/signature checks, both extension installations, and ARR configuration passed. Acceptance then failed with CommandNotFoundException at the private-package/sentinel stage, before its first successful package assertion. The initial sanitized report lacks a source line, so the failing executable lookup cannot be confirmed from it. No Local Service/IIS behavior acceptance pass is claimed.

A narrow retry patch selects the first ApplicationInfo.Path as one executable rather than projecting potentially multiple Source values. It records only candidate counts, validates Python execution before provisioning, and preserves fixed script/line coordinates and fixed child-check names on failures. Multiple PATH candidates are a hypothesis, not a confirmed cause of the retained failure. No acceptance conditions were weakened.

Local validation of the patch: 12 IIS launcher tests, 8 IIS connection tests and 16 CI tests passed; git diff whitespace check passed. Native execution is pending the next Windows run.
