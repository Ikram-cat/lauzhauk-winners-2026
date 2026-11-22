package alt

// Kotlin/Wasm has no timezone database, so all Europe/Zurich conversions go through
// the browser's Intl API (which always carries the current IANA rules).

@JsFun(
    """
    (iso, withDate) => {
      if (!iso) return "";
      const d = new Date(iso);
      if (isNaN(d.getTime())) return "";
      const opts = withDate
        ? { timeZone: 'Europe/Zurich', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }
        : { timeZone: 'Europe/Zurich', hour: '2-digit', minute: '2-digit', hour12: false };
      const parts = new Intl.DateTimeFormat('en-GB', opts).formatToParts(d);
      const g = (t) => { const p = parts.find((x) => x.type === t); return p ? p.value : ''; };
      const hh = g('hour') === '24' ? '00' : g('hour');
      return withDate ? g('day') + '.' + g('month') + ' ' + hh + ':' + g('minute') : hh + ':' + g('minute');
    }
    """
)
private external fun jsFmtZurich(iso: String, withDate: Boolean): String

@JsFun(
    """
    (localIso) => {
      const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(localIso);
      if (!m) return "";
      const y = +m[1], mo = +m[2], d = +m[3], h = +m[4], mi = +m[5];
      const guess = Date.UTC(y, mo - 1, d, h, mi);
      const dtf = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Zurich', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });
      const parts = dtf.formatToParts(new Date(guess));
      const g = (t) => { const p = parts.find((x) => x.type === t); return p ? +p.value : 0; };
      const asUTC = Date.UTC(g('year'), g('month') - 1, g('day'), g('hour') % 24, g('minute'));
      const instant = guess - (asUTC - guess);
      return new Date(instant).toISOString().replace('.000Z', 'Z');
    }
    """
)
private external fun jsLocalToInstant(localIso: String): String

@JsFun(
    """
    () => {
      const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Zurich', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
      const g = (t) => { const p = parts.find((x) => x.type === t); return p ? p.value : ''; };
      return g('year') + '-' + g('month') + '-' + g('day');
    }
    """
)
private external fun jsToday(): String

actual fun fmtZurich(iso: String, withDate: Boolean): String? =
    jsFmtZurich(iso, withDate).takeIf { it.isNotBlank() }

actual fun localToInstantIso(localIso: String): String? {
    val v = jsLocalToInstant(localIso)
    return if (v.isBlank()) null else v
}

actual fun todayZurichDate(): String = jsToday()

@JsFun(
    """
    () => {
      const p = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Zurich', hourCycle: 'h23', hour: '2-digit', minute: '2-digit', second: '2-digit' }).formatToParts(new Date());
      const g = (t) => { const x = p.find((q) => q.type === t); return x ? +x.value : 0; };
      return g('hour') * 3600 + g('minute') * 60 + g('second') + (Date.now() % 1000) / 1000;
    }
    """
)
private external fun jsZurichSeconds(): Double

@JsFun("() => !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches)")
private external fun jsReduced(): Boolean

@JsFun("(k, s) => { try { const v = (s ? sessionStorage : localStorage).getItem(k); return v === null ? '' : v; } catch (e) { return ''; } }")
private external fun jsKvGet(key: String, session: Boolean): String

@JsFun("(k, v, s) => { try { (s ? sessionStorage : localStorage).setItem(k, v); } catch (e) {} }")
private external fun jsKvSet(key: String, value: String, session: Boolean)

actual fun zurichSecondsOfDay(): Double = jsZurichSeconds()
actual fun prefersReducedMotion(): Boolean = jsReduced()
actual fun kvGet(key: String, session: Boolean): String = jsKvGet(key, session)
actual fun kvSet(key: String, value: String, session: Boolean) = jsKvSet(key, value, session)

actual val canLoadSvgResources: Boolean = true
