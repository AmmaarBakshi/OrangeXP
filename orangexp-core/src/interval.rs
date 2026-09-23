//! Half-open integer interval helpers used by timetable calculations.

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct Interval {
    pub start: i64,
    pub end: i64,
}

impl Interval {
    pub fn new(start: i64, end: i64) -> Self {
        Self { start, end }
    }

    pub fn len(self) -> i64 {
        (self.end - self.start).max(0)
    }
}

/// Sorts and merges overlapping or touching intervals, dropping empty ones.
pub(crate) fn merge(mut intervals: Vec<Interval>) -> Vec<Interval> {
    intervals.retain(|i| i.len() > 0);
    intervals.sort();
    let mut out: Vec<Interval> = Vec::with_capacity(intervals.len());
    for i in intervals {
        match out.last_mut() {
            Some(last) if i.start <= last.end => last.end = last.end.max(i.end),
            _ => out.push(i),
        }
    }
    out
}

/// `base − remove`, both merged beforehand.
pub(crate) fn subtract(base: &[Interval], remove: &[Interval]) -> Vec<Interval> {
    let mut out = Vec::new();
    for b in base {
        let mut cursor = b.start;
        for r in remove.iter().filter(|r| r.end > b.start && r.start < b.end) {
            if r.start > cursor {
                out.push(Interval::new(cursor, r.start));
            }
            cursor = cursor.max(r.end);
        }
        if cursor < b.end {
            out.push(Interval::new(cursor, b.end));
        }
    }
    out
}

pub(crate) fn total(intervals: &[Interval]) -> i64 {
    intervals.iter().map(|i| i.len()).sum()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn iv(s: i64, e: i64) -> Interval {
        Interval::new(s, e)
    }

    #[test]
    fn merge_and_subtract() {
        let merged = merge(vec![iv(5, 8), iv(0, 3), iv(2, 4), iv(8, 9), iv(10, 10)]);
        assert_eq!(merged, vec![iv(0, 4), iv(5, 9)]);
        let rest = subtract(
            &[iv(0, 100)],
            &merge(vec![iv(10, 20), iv(15, 30), iv(90, 120)]),
        );
        assert_eq!(rest, vec![iv(0, 10), iv(30, 90)]);
        assert_eq!(total(&rest), 70);
        assert_eq!(subtract(&[iv(0, 10)], &[iv(-5, 50)]), vec![]);
    }
}
