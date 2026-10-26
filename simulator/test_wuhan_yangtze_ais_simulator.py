import math
import unittest

from wuhan_yangtze_ais_simulator import (
    END, START, Vessel, build_routes, bearing_deg, KNOT_TO_MPS, haversine_m, load_route_path, offset,
)

# Wuhan Second Bridge virtual red buoys, 长江武汉航道局 2026-09-24 notice items 5 and 6.
SECOND_BRIDGE_RED_BUOYS = ((114.322833, 30.605917), (114.320000, 30.602389))


def off_polyline_m(path, point):
    return min(haversine_m(a, point) + haversine_m(point, b) - haversine_m(a, b) for a, b in zip(path, path[1:]))


def starboard_offset_m(path, point):
    """Smallest distance from point to the polyline, positive when it lies to starboard."""
    best = None
    for a, b in zip(path, path[1:]):
        ref = math.cos(math.radians(a[1]))
        ax, ay, bx, by = a[0] * ref, a[1], b[0] * ref, b[1]
        px, py = point[0] * ref, point[1]
        dx, dy = bx - ax, by - ay
        t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        cx, cy = ax + t * dx, ay + t * dy
        distance = math.hypot(px - cx, py - cy) * 111320.0
        side = 1 if dx * (py - ay) - dy * (px - ax) < 0 else -1
        if best is None or distance < abs(best):
            best = side * distance
    return best


class WuhanScenarioTest(unittest.TestCase):
    def test_constant_speed_and_terminal_stop(self):
        boat = Vessel('1', ((114.3, 30.58), (114.3, 30.59)), 8, 8)
        position, speed, heading = boat.sample(60)
        self.assertAlmostEqual(haversine_m(boat.start, position), 8*KNOT_TO_MPS*60, places=3)
        self.assertEqual((speed, heading), (8, 0))
        self.assertEqual(boat.sample(10000)[:2], (boat.end, 0))
        self.assertEqual(boat.sample(20000)[:2], (boat.end, 0))

    def test_speed_ramp_is_continuous_and_integrated(self):
        boat = Vessel('1', ((114.3, 30.58), (114.3, 30.68)), 12, 3)
        position, speed, _ = boat.sample(120)
        self.assertAlmostEqual(haversine_m(boat.start, position), (3*30 + 7.5*90)*KNOT_TO_MPS, places=3)
        self.assertEqual(speed, 12)
        for boundary in (30, 120):
            self.assertLess(haversine_m(boat.sample(boundary-.001)[0], boat.sample(boundary+.001)[0]), .02)

    def test_polyline_switches_leg_and_course(self):
        a, b, c = (114.3, 30.58), (114.3, 30.59), (114.31, 30.59)
        boat = Vessel('1', (a, b, c), 8, 8)
        first_leg_s = haversine_m(a, b) / (8 * KNOT_TO_MPS)
        self.assertAlmostEqual(boat.sample(first_leg_s - 1)[2], bearing_deg(a, b), places=6)
        position, _, heading = boat.sample(first_leg_s + 10)
        self.assertAlmostEqual(heading, bearing_deg(b, c), places=6)
        self.assertAlmostEqual(haversine_m(b, position), 80 * KNOT_TO_MPS, delta=.05)
        self.assertEqual(boat.sample(10000)[:2], (c, 0))

    def test_route_file_keeps_original_encounter_leg(self):
        path = load_route_path()
        self.assertLess(haversine_m(path[0], offset(START, -280)), 1.0)
        self.assertLess(haversine_m(path[1], offset(END, -280)), 1.0)

    def test_second_bridge_buoys_pass_to_starboard(self):
        path = load_route_path()
        for buoy in SECOND_BRIDGE_RED_BUOYS:
            offset_m = starboard_offset_m(path, buoy)
            self.assertGreaterEqual(offset_m, 80)
            self.assertLessEqual(offset_m, 180)

    def test_head_on_starts_safe_and_converges(self):
        own, opposing, slow = build_routes()
        self.assertEqual(own.mmsi, '123456789')
        self.assertEqual(len({b.mmsi for b in (own, opposing, slow)}), 3)
        self.assertGreater(haversine_m(own.start, opposing.start)/(6*KNOT_TO_MPS), 900)
        self.assertLess(abs((own.sample(0)[2]-opposing.sample(0)[2])%360-180), .1)
        distances = [haversine_m(own.sample(t)[0], opposing.sample(t)[0]) for t in range(700)]
        self.assertLess(min(distances), 100)
        self.assertGreater(distances[-1], 2500)
        self.assertGreater(own.cruise_kn, slow.cruise_kn)

    def test_all_positions_stay_on_finite_routes(self):
        for boat in build_routes():
            for t in range(1500):
                position, speed, _ = boat.sample(t)
                self.assertGreaterEqual(speed, 0)
                self.assertLessEqual(speed, boat.cruise_kn)
                self.assertLess(off_polyline_m(boat.path, position), .02)


if __name__ == '__main__':
    unittest.main()
