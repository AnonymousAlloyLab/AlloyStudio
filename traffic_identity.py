"""Application quota identity and administration admission (AP01-C04/C05/C11).

IP addresses are quota identities, never people or authentication. Trusted
proxies are exact canonical addresses and default to none; neither loopback nor
a forwarding header grants trust. Forwarded metadata from an untrusted peer is
ignored entirely. From a trusted peer it must decode strictly, or the request
has no identity: there is no fallback to the proxy's own address.

HTTP admission still reserves by the physical TCP peer before headers are read
(traffic_http.process_request). Nothing here changes that earlier boundary.
"""
import ipaddress

FORWARDED_HEADER = 'x-forwarded-for'
MAX_FORWARDED_BYTES = 4096
MAX_FORWARDED_HOPS = 32
ABSENT = 'absent'
REJECTED = 'rejected'


def canonical_address(text):
    """Return a strict canonical IP atom, or None.

    Rejects ports, zones, brackets, whitespace, empty tokens, IPv4-mapped IPv6
    aliases and any spelling other than Python's canonical form, so one address
    has exactly one accepted spelling.
    """
    if type(text) is not str or not 0 < len(text) <= 45 or not text.isascii():
        return None
    try:
        address = ipaddress.ip_address(text)
    except ValueError:
        return None
    if isinstance(address, ipaddress.IPv6Address) and (
            address.ipv4_mapped is not None or address.scope_id is not None):
        return None
    canonical = str(address)
    return canonical if canonical == text else None


def socket_identity(peer):
    """Canonicalize the kernel-supplied TCP peer; IPv4-mapped peers become IPv4."""
    if type(peer) is not str:
        return None
    try:
        address = ipaddress.ip_address(peer)
    except ValueError:
        return None
    if isinstance(address, ipaddress.IPv6Address) and address.scope_id is not None:
        return None
    if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped is not None:
        return str(address.ipv4_mapped)
    return str(address)


def trusted_proxies(values):
    """Exact operator-configured proxy addresses; every entry must be canonical."""
    result = []
    for value in values:
        address = canonical_address(value)
        if address is None:
            raise ValueError('Trusted proxies must be canonical IP addresses without ports or zones.')
        if address not in result:
            result.append(address)
    return frozenset(result)


def parse_forwarding(raw_items):
    """Bounded X-Forwarded-For decoding over raw header pairs (multiplicity kept).

    Returns ABSENT, REJECTED, or the nonempty hop list in wire order
    (client first, nearest proxy last), mirroring Identity.parseForwarding.
    """
    fields = [value for name, value in raw_items if name.lower() == FORWARDED_HEADER]
    if not fields:
        return ABSENT
    if len(fields) != 1:
        return REJECTED
    try:
        # http.client decodes header bytes as ISO-8859-1; recover the wire size.
        encoded = fields[0].encode('iso-8859-1')
    except UnicodeError:
        return REJECTED
    if len(encoded) > MAX_FORWARDED_BYTES:
        return REJECTED
    tokens = fields[0].split(',')
    if len(tokens) > MAX_FORWARDED_HOPS:
        return REJECTED
    hops = []
    for token in tokens:
        address = canonical_address(token.strip(' \t'))
        if address is None:
            return REJECTED
        hops.append(address)
    return hops


def nearest_untrusted(trusted, hops):
    """Scan from the nearest hop toward the client, skipping trusted hops."""
    for hop in reversed(hops):
        if hop not in trusted:
            return hop
    return None


def resolve_identity(trusted, peer, raw_items):
    """Identity.resolveDecodedQuotaIdentity: the quota identity, or None if rejected."""
    socket_peer = socket_identity(peer)
    if socket_peer is None or socket_peer not in trusted:
        return socket_peer
    forwarded = parse_forwarding(raw_items)
    if forwarded == ABSENT or forwarded == REJECTED:
        return None
    return nearest_untrusted(trusted, forwarded)


class AdminNetworkPolicy:
    """Operator network allowlist for every administration route; default deny.

    The same networks must be configured at the IIS edge. Allowed sources can
    still share the sign-in failure limit; this is not an availability guarantee.
    """

    def __init__(self, networks=()):
        parsed = []
        for value in networks:
            if type(value) is not str or not value.isascii() or value.strip() != value:
                raise ValueError('Administration networks must be canonical CIDR blocks.')
            try:
                network = ipaddress.ip_network(value, strict=True)
            except ValueError:
                raise ValueError('Administration networks must be canonical CIDR blocks.') from None
            if str(network) != value:
                raise ValueError('Administration networks must be canonical CIDR blocks.')
            if network not in parsed:
                parsed.append(network)
        self.networks = tuple(parsed)

    def admits(self, identity):
        if not self.networks or canonical_address(identity) is None:
            return False
        try:
            address = ipaddress.ip_address(identity)
        except ValueError:
            return False
        return any(address.version == network.version and address in network
                   for network in self.networks)
