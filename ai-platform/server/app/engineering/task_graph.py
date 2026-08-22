from dataclasses import dataclass

@dataclass(frozen=True)
class TaskSpec:
    role:str; title:str; description:str; depends_on:tuple[str,...]=()

def default_graph(goal:str)->list[TaskSpec]:
    return [
      TaskSpec('planner','Understand requirements','Turn the user goal into explicit acceptance criteria, constraints, risks and a concrete implementation plan.'),
      TaskSpec('architect','Architecture review','Inspect the repository and propose the smallest safe architecture changes.',('planner',)),
      TaskSpec('implementer','Implement changes','Apply the planned code, configuration, migration and documentation changes inside the isolated workspace.',('architect',)),
      TaskSpec('tester','Run quality gates','Compile, lint and test applicable project components.',('implementer',)),
      TaskSpec('security','Security review','Scan for secrets, risky execution patterns and unsafe configuration.',('implementer',)),
      TaskSpec('dependency','Dependency intelligence','Inspect dependency manifests, lockfiles and risky or non-reproducible dependency declarations.',('implementer',)),
      TaskSpec('reviewer','Independent code review','Review requirements coverage, regressions and maintainability.',('tester','security','dependency')),
      TaskSpec('repair','Repair failures','Fix reproducible test, review or security failures with bounded attempts.',('reviewer',)),
      TaskSpec('qa','Acceptance verification','Verify the original goal against evidence from tests, scans and changed files.',('repair',)),
      TaskSpec('release','Checkpoint release','Create a reproducible checkpoint, code index and release manifest.',('qa',)),
    ]
