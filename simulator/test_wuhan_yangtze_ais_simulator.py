import unittest

from wuhan_yangtze_ais_simulator import Vessel, build_routes, KNOT_TO_MPS, haversine_m


class WuhanScenarioTest(unittest.TestCase):
    def test_constant_speed_and_terminal_stop(self):
        boat = Vessel('1', (114.3, 30.58), (114.3, 30.59), 8, 8)
        position, speed, heading = boat.sample(60)
        self.assertAlmostEqual(haversine_m(boat.start, position), 8*KNOT_TO_MPS*60, places=3)
        self.assertEqual((speed, heading), (8, 0))
        self.assertEqual(boat.sample(10000)[:2], (boat.end, 0))
        self.assertEqual(boat.sample(20000)[:2], (boat.end, 0))

    def test_speed_ramp_is_continuous_and_integrated(self):
        boat = Vessel('1', (114.3, 30.58), (114.3, 30.68), 12, 3)
        position, speed, _ = boat.sample(120)
        self.assertAlmostEqual(haversine_m(boat.start, position), (3*30 + 7.5*90)*KNOT_TO_MPS, places=3)
        self.assertEqual(speed, 12)
        for boundary in (30, 120):
            self.assertLess(haversine_m(boat.sample(boundary-.001)[0], boat.sample(boundary+.001)[0]), .02)

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
            length = haversine_m(boat.start, boat.end)
            for t in range(1000):
                position, speed, _ = boat.sample(t)
                self.assertGreaterEqual(speed, 0)
                self.assertLessEqual(speed, boat.cruise_kn)
                self.assertAlmostEqual(haversine_m(boat.start, position)+haversine_m(position, boat.end), length, delta=.02)


if __name__ == '__main__':
    unittest.main()
