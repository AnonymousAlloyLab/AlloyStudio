# Native IIS third failed run

Run: https://github.com/AnonymousAlloyLab/AlloyStudio/actions/runs/37299449565
Revision: 9f45ab2663c43c03e64bb85dbdbc093f20e2d244

Real Local Service installation and readiness passed. The native checks also passed all 378 packaged JVM checks, seven JAR hashes, private ACLs, loopback binding, public asset loading, admin default-deny, and public health reporting 181 exercises. Failure occurred between the health assertion and first POST channel assertion; no complete deployment acceptance pass is claimed.

The native .NET client left Expect100Continue at its default true. The production strict decoder rejects Expect with 417; the browser client does not request this protocol mode. The retry explicitly disables the native client's automatic Expect header without changing production acceptance rules. This is a concrete client-precondition mismatch, but the prior report did not preserve the response code, so this run alone does not confirm its HTTP status.

Microsoft documents the .NET Framework default and POST behavior at https://learn.microsoft.com/en-us/dotnet/api/system.net.servicepointmanager.expect100continue?view=netframework-4.8.1 . New diagnostics retain the numeric last HTTP status and fixed failed-check name, without response bodies, tokens or exception values.
