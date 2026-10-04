"""Pure, strict numeric guards for the finite traffic configuration profile.

Use exact Python scalar types: booleans and numeric subclasses are not counts
or durations. A float duration is checked as its exact integer ratio so very
large integers cannot overflow a float conversion and nonfinite floats fail
closed. No caller-controlled value is included in diagnostics.
"""


def validated_int(value, minimum=1, maximum=67108864):
    if (type(minimum) is not int or type(maximum) is not int
            or minimum < 0 or maximum < minimum):
        raise ValueError('Invalid integer bounds.')
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError('Traffic counts and byte limits must be bounded integers.')
    return value


def validated_seconds(value, minimum_zero=False, maximum=86400):
    if (type(minimum_zero) is not bool or type(maximum) is not int
            or maximum <= 0):
        raise ValueError('Invalid duration bounds.')
    if type(value) is not int and type(value) is not float:
        raise ValueError('Traffic durations must be bounded finite numbers.')
    try:
        numerator, denominator = value.as_integer_ratio()
    except (ValueError, OverflowError):
        raise ValueError('Traffic durations must be bounded finite numbers.') from None
    if denominator <= 0:
        raise ValueError('Traffic durations must be bounded finite numbers.')
    if (numerator < 0 or (not minimum_zero and numerator == 0)
            or numerator > maximum * denominator):
        raise ValueError('Traffic durations must be bounded finite numbers.')
    return value
