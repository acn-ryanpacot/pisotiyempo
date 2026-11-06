import { addDays } from "./util";

// PSE closes on every Philippine regular holiday and special non-working day.
// 2026: Proclamation No. 1006 (s. 2025), plus Proc. 1189 (Eid'l Fitr) and Proc. 1264 (Eid'l Adha).
// 2027: Proclamation No. 1427 (s. 2026). Eid'l Fitr and Eid'l Adha 2027 are proclaimed separately each year:
// add them, and any sudden closure (typhoons, ad-hoc declarations), through POST /v1/admin/closure.
// Update this list every September when Malacañang publishes the next year's holidays.
export const HOLIDAYS: Record<string, string> = {
  "2026-01-01": "New Year's Day",
  "2026-02-17": "Chinese New Year",
  "2026-03-20": "Eid'l Fitr",
  "2026-04-02": "Maundy Thursday",
  "2026-04-03": "Good Friday",
  "2026-04-04": "Black Saturday",
  "2026-04-09": "Araw ng Kagitingan",
  "2026-05-01": "Labor Day",
  "2026-05-27": "Eid'l Adha",
  "2026-06-12": "Independence Day",
  "2026-08-21": "Ninoy Aquino Day",
  "2026-08-31": "National Heroes Day",
  "2026-11-01": "All Saints' Day",
  "2026-11-02": "All Souls' Day",
  "2026-11-30": "Bonifacio Day",
  "2026-12-08": "Feast of the Immaculate Conception",
  "2026-12-24": "Christmas Eve",
  "2026-12-25": "Christmas Day",
  "2026-12-30": "Rizal Day",
  "2026-12-31": "Last Day of the Year",

  "2027-01-01": "New Year's Day",
  "2027-02-06": "Chinese New Year",
  "2027-03-25": "Maundy Thursday",
  "2027-03-26": "Good Friday",
  "2027-03-27": "Black Saturday",
  "2027-04-09": "Araw ng Kagitingan",
  "2027-05-01": "Labor Day",
  "2027-06-12": "Independence Day",
  "2027-08-21": "Ninoy Aquino Day",
  "2027-08-30": "National Heroes Day",
  "2027-11-01": "All Saints' Day",
  "2027-11-02": "All Souls' Day",
  "2027-11-30": "Bonifacio Day",
  "2027-12-08": "Feast of the Immaculate Conception",
  "2027-12-24": "Christmas Eve",
  "2027-12-25": "Christmas Day",
  "2027-12-30": "Rizal Day",
  "2027-12-31": "Last Day of the Year",
};

/** Ad-hoc closures added at runtime, keyed by ISO date. */
export type Closures = Record<string, string>;

export const CLOSURES_KEY = "calendar:closures";

export class TradingCalendar {
  constructor(private readonly extra: Closures = {}) {}

  static async load(kv: KVNamespace): Promise<TradingCalendar> {
    return new TradingCalendar((await kv.get<Closures>(CLOSURES_KEY, "json")) ?? {});
  }

  closureName(date: string): string | null {
    const day = new Date(`${date}T00:00:00Z`).getUTCDay();
    if (day === 0 || day === 6) return "Weekend";
    return this.extra[date] ?? HOLIDAYS[date] ?? null;
  }

  isTradingDay(date: string): boolean {
    return this.closureName(date) === null;
  }

  lastTradingDayOnOrBefore(date: string): string {
    let d = date;
    // A year can't have more than a few weeks of consecutive closures; the guard just prevents a bad list from hanging.
    for (let i = 0; i < 30 && !this.isTradingDay(d); i++) d = addDays(d, -1);
    return d;
  }

  /** Holidays and closures from [from] onward, weekends excluded. */
  upcoming(from: string): Array<{ date: string; name: string }> {
    const all = { ...HOLIDAYS, ...this.extra };
    return Object.entries(all)
      .filter(([date]) => date >= from)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([date, name]) => ({ date, name }));
  }
}
