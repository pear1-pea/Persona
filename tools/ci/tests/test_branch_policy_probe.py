import unittest


class BranchPolicyAcceptanceProbe(unittest.TestCase):
    def test_intentional_required_check_failure(self):
        self.fail('Intentional protected-branch acceptance failure; remove before merge')
