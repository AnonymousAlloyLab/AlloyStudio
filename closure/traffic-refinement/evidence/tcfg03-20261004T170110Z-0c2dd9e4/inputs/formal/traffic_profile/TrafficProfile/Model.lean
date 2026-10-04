import TrafficConfig.Scalar

/- Independent data boundary. No validity assumptions are stored in either record. -/
namespace AlloyStudio.HttpProfile.Model

open AlloyStudio.TrafficConfig

structure RawProfile where
  public_handlers : Scalar
  control_handlers : Scalar
  public_burst : Scalar
  public_rate : Scalar
  control_burst : Scalar
  control_rate : Scalar
  peer_burst : Scalar
  peer_rate : Scalar
  peer_entries : Scalar
  peer_idle_seconds : Scalar
  backlog : Scalar
  control_backlog : Scalar
  line_bytes : Scalar
  header_bytes : Scalar
  header_count : Scalar
  header_seconds : Scalar
  body_seconds : Scalar
  write_seconds : Scalar
  idle_seconds : Scalar
  json_depth : Scalar
  response_bytes : Scalar
  public_cache_bytes : Scalar
  public_cache_entries : Scalar

/-- Noninteger variants remain invalid under the separate field-domain predicate. -/
def integerValue : Scalar → Int
  | .integer value => value
  | .floating _ _ => 0
  | .invalid _ => 0

structure InitialState where
  limit : Int
  capacity : Int
  rate : Int
  credit : Int
  last : Int
  active : Nat
  peak : Nat
  accepted : Nat
  rejected : Nat
  owners : List Nat
  anonymous_owners : List Nat
  peers : List Nat

end AlloyStudio.HttpProfile.Model
