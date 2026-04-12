from pathlib import Path


def test_deeprl_workflow_has_no_removed_deeprlpolicy_reference():
    source = Path(
        "deep-reinforcement-learning-dqn/src/main/java/ai/intelliswarm/swarmai/examples/deeprl/DeepRLWorkflow.java"
    ).read_text(encoding="utf-8")

    assert "ai.intelliswarm.swarmai.enterprise.rl.deep.DeepRLPolicy" not in source
    assert "new DeepRLPolicy(" not in source
