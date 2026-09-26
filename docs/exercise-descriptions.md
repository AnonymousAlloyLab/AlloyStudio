# Live programming exercise descriptions

Natural-language requirements for all 181 bundled exercises. Each requirement
uses its exercise's original model declarations and facts. Temporal descriptions
distinguish the initial state, later states, and properties that hold at every state.

These are task specifications; predicate implementations are kept private.

## classroom_fol

### classroom_fol-inv1

Every person is a student.

### classroom_fol-inv2

There are no teachers.

### classroom_fol-inv3

No person is both a student and a teacher.

### classroom_fol-inv4

Every person is a student, a teacher, or both.

### classroom_fol-inv5

At least one teacher teaches at least one class.

### classroom_fol-inv6

Every teacher teaches at least one class. This does not require any teachers to exist.

### classroom_fol-inv7

Every class is taught by at least one teacher.

### classroom_fol-inv8

Each teacher teaches at most one class; teaching no classes is allowed.

### classroom_fol-inv9

Each class is taught by at most one teacher; having no teacher is allowed. People outside the teacher set are not counted for this limit.

### classroom_fol-inv10

In every class, every student is assigned to at least one group.

### classroom_fol-inv11

If a class has any person assigned to a group, at least one teacher teaches that class.

### classroom_fol-inv12

Every teacher teaches at least one class in which at least one person is assigned to a group.

### classroom_fol-inv13

Every tutoring link goes from a teacher to a student. No tutoring links are required to exist.

### classroom_fol-inv14

Everyone who teaches a class must tutor every person assigned to a group in that class. This applies to everyone who teaches it, even if they are not in the teacher set.

### classroom_fol-inv15

Every person must be reachable from some teacher through one or more tutoring links, following each link from tutor to tutee. This also applies to people who are themselves teachers.

## classroom_rl

### classroom_rl-inv1

Every person is a student.

### classroom_rl-inv2

There are no teachers.

### classroom_rl-inv3

No person is both a student and a teacher.

### classroom_rl-inv4

Every person is a student, a teacher, or both.

### classroom_rl-inv5

At least one teacher teaches at least one class.

### classroom_rl-inv6

Every teacher teaches at least one class. This does not require any teachers to exist.

### classroom_rl-inv7

Every class is taught by at least one teacher.

### classroom_rl-inv8

Each teacher teaches at most one class; teaching no classes is allowed.

### classroom_rl-inv9

Each class is taught by at most one teacher; having no teacher is allowed. People outside the teacher set are not counted for this limit.

### classroom_rl-inv10

In every class, every student is assigned to at least one group.

### classroom_rl-inv11

If a class has any person assigned to a group, at least one teacher teaches that class.

### classroom_rl-inv12

Every teacher teaches at least one class in which at least one person is assigned to a group.

### classroom_rl-inv13

Every tutoring link goes from a teacher to a student. No tutoring links are required to exist.

### classroom_rl-inv14

Everyone who teaches a class must tutor every person assigned to a group in that class. This applies to everyone who teaches it, even if they are not in the teacher set.

### classroom_rl-inv15

Every person must be reachable from some teacher through one or more tutoring links, following each link from tutor to tutee. This also applies to people who are themselves teachers.

## coursesNew

### coursesNew-inv1

Only students may be enrolled in courses. Students are allowed to have no enrollments.

### coursesNew-inv2

Only professors may teach courses. Professors are allowed to teach no courses.

### coursesNew-inv3

Every course is taught by at least one person. This requirement alone does not require that person to be a professor.

### coursesNew-inv4

Every project belongs to exactly one course.

### coursesNew-inv5

Every project has at least one person working on it, and everyone working on a project is a student.

### coursesNew-inv6

Every project a person works on must belong to at least one course in which that person is enrolled.

### coursesNew-inv7

For each course, a person works on at most one of its projects. Working on none of a course's projects is allowed.

### coursesNew-inv8

Nobody is enrolled in a course that they themselves teach.

### coursesNew-inv9

If two distinct people teach a common course, neither may be enrolled in any course taught by the other. The enrollment need not be in the course that they teach together for this restriction to apply.

### coursesNew-inv10

Only students may receive grades in courses.

### coursesNew-inv11

A person may receive a grade in a course only if they are enrolled in that course.

### coursesNew-inv12

Each person has at most one grade in each course. Having no grade is allowed.

### coursesNew-inv13

Anyone who receives the highest grade in a course must work on at least one project belonging to that course.

### coursesNew-inv14

No two distinct people may work together on more than one project.

### coursesNew-inv15

When two distinct people work on the same project in a course and both have grades in that course, every grade of either person must equal or be immediately adjacent to at least one grade of the other in the grade ordering. If either person has no grade in the course, this requirement imposes no comparison on that pair.

## coursesOld

### coursesOld-inv1

Only students may be enrolled in courses. Students are allowed to have no enrollments.

### coursesOld-inv2

Only professors may teach courses. Professors are allowed to teach no courses.

### coursesOld-inv3

Every course is taught by at least one person. This requirement alone does not require that person to be a professor.

### coursesOld-inv4

Every project belongs to exactly one course.

### coursesOld-inv5

Every project has at least one person working on it, and everyone working on a project is a student.

### coursesOld-inv6

Every project a person works on must belong to at least one course in which that person is enrolled.

### coursesOld-inv7

For each course, a person works on at most one of its projects. Working on none of a course's projects is allowed.

### coursesOld-inv8

Nobody is enrolled in a course that they themselves teach.

### coursesOld-inv9

If two distinct people teach a common course, neither may be enrolled in any course taught by the other. The enrollment need not be in the course that they teach together for this restriction to apply.

### coursesOld-inv10

Only students may receive grades in courses.

### coursesOld-inv11

A person may receive a grade in a course only if they are enrolled in that course.

### coursesOld-inv12

Each person has at most one grade in each course. Having no grade is allowed.

### coursesOld-inv13

Anyone who receives the highest grade in a course must work on at least one project belonging to that course.

### coursesOld-inv14

No two distinct people may work together on more than one project.

### coursesOld-inv15

When two distinct people work on the same project in a course and both have grades in that course, every grade of either person must equal or be immediately adjacent to at least one grade of the other in the grade ordering. If either person has no grade in the course, this requirement imposes no comparison on that pair.

## cv_v1

### cv_v1-inv1

Every work a user makes visible must belong to that user's profile.

### cv_v1-inv2

Every work in a user's profile must have been supplied either by an institution or by that same user.

### cv_v1-inv3

Within any user's profile, two distinct works supplied by the same source must not share an identifier.

### cv_v1-inv4

No two distinct works that the same user makes visible may be connected by a chain of works in which consecutive works share an identifier. The chain may pass through any works, including works outside that user's profile.

## cv_v2

### cv_v2-inv1

Every work a user makes visible must belong to that user's profile.

### cv_v2-inv2

Every work in a user's profile must have been supplied either by an institution or by that same user.

### cv_v2-inv3

Within any user's profile, two distinct works supplied by the same source must not share an identifier.

### cv_v2-inv4

No two distinct works that the same user makes visible may be connected by a chain of works in which consecutive works share an identifier and every work in the chain belongs to that user's profile. Works outside that profile do not connect visible works for this requirement.

## graphs

### graphs-inv1

Every directed edge must have an edge in the reverse direction as well. Self-loops are permitted.

### graphs-inv2

No pair of nodes may have edges in both directions between them. Self-loops are also forbidden.

### graphs-inv3

There must be no directed cycles: no node may reach itself by following one or more edges.

### graphs-inv4

There must be a directed edge from every node to every node, including an edge from each node to itself.

### graphs-inv5

No node may have an edge to itself.

### graphs-inv6

Ignoring edge directions, every node must be reachable from every other node. A node is considered reachable from itself without taking an edge.

### graphs-inv7

Following edge directions, every node must be reachable from every other node. A node is considered reachable from itself without taking an edge.

### graphs-inv8

Whenever one node can reach another by following one or more directed edges, there must also be a direct edge from the first node to the second. In particular, any node on a directed cycle must have a self-loop.

## lts

### lts-inv1

Every state must have at least one outgoing transition, labelled by some event and leading to some state.

### lts-inv2

There must be exactly one initial state.

### lts-inv3

For any state and event, there may be at most one destination state for a transition with that event. An event need not be enabled at every state.

### lts-inv4

Every state must be reachable from at least one initial state by following one or more transitions. This includes initial states themselves; a zero-transition path does not count.

### lts-inv5

All states must enable exactly the same set of events. Their transition destinations may differ, and the shared set of enabled events may be empty.

### lts-inv6

Every event must label at least one transition somewhere in the system.

### lts-inv7

Every state reachable from an initial state by one or more transitions must be able to reach some initial state by one or more transitions. The return destination need not be the initial state where the first path began.

## productionLineNew

### productionLineNew-inv1

Every worker must be either a human or a robot.

### productionLineNew-inv2

Every workstation must have at least one worker, and every worker must be assigned to exactly one workstation.

### productionLineNew-inv3

Every component must be assigned to exactly one workstation.

### productionLineNew-inv4

Every component must have at least one direct part, and materials must have no parts.

### productionLineNew-inv5

A workstation must not have both human workers and robot workers assigned to it. A workstation with no workers satisfies this requirement.

### productionLineNew-inv6

No component may contain itself as a part, either directly or through a chain of one or more part relationships.

### productionLineNew-inv7

A component with at least one dangerous direct part must itself be marked dangerous.

### productionLineNew-inv8

No human may work at any workstation assigned to a dangerous component.

### productionLineNew-inv9

Every workstation except end must have exactly one immediate successor, and end must have none. Starting at begin and taking zero or more successor steps must reach every workstation.

### productionLineNew-inv10

For every component, each workstation assigned to any of its direct parts that are themselves components must be able to reach a workstation assigned to the containing component by one or more successor steps. Parts with no assigned workstation impose no ordering requirement.

## productionLine_v1

### productionLine_v1-inv1

Every component must have at least one direct part.

### productionLine_v1-inv2

No component may contain itself as a part, either directly or through a chain of one or more part relationships.

### productionLine_v1-inv3

Each component must have at least one robot at its position.

### productionLine_v1-inv4

Every direct part that is itself a component must be positioned at or before the component that contains it in the ordering of positions. The two components may occupy the same position.

## productionLine_v2

### productionLine_v2-inv1

Every worker must be either a human or a robot.

### productionLine_v2-inv2

Every workstation must have at least one worker, and every worker must be assigned to exactly one workstation.

### productionLine_v2-inv3

Every component must be assigned to exactly one workstation.

### productionLine_v2-inv4

Every component must have at least one direct part, and materials must have no parts.

### productionLine_v2-inv5

A workstation must not have both human workers and robot workers assigned to it. A workstation with no workers satisfies this requirement.

### productionLine_v2-inv6

No component may contain itself as a part, either directly or through a chain of one or more part relationships.

### productionLine_v2-inv7

A component with at least one dangerous direct part must itself be marked dangerous.

### productionLine_v2-inv8

No human may work at any workstation assigned to a dangerous component.

### productionLine_v2-inv9

Every workstation except end must have exactly one immediate successor, and end must have none. Starting at begin and taking zero or more successor steps must reach every workstation.

### productionLine_v2-inv10

For every component, each workstation assigned to any of its direct parts that are themselves components must be able to reach a workstation assigned to the containing component by one or more successor steps. Parts with no assigned workstation impose no ordering requirement.

## socialMedia

### socialMedia-inv1

Every photo must be posted by exactly one user.

### socialMedia-inv2

No user may follow themselves.

### socialMedia-inv3

Every non-advertisement photo a user sees must have been posted by someone that user follows. Advertisements are unrestricted by this requirement.

### socialMedia-inv4

Any user who posts at least one advertisement must post only advertisements.

### socialMedia-inv5

Every influencer must be followed by all other users and must not follow themselves.

### socialMedia-inv6

Every influencer must post at least one photo dated on each day in the model.

### socialMedia-inv7

A user's suggested users must be exactly the users followed by someone that user follows, excluding the user themselves and anyone they already follow.

### socialMedia-inv8

Every advertisement a user sees must have been posted by someone they follow or someone suggested to them.

## trainStationNew

### trainStationNew-inv1

The station must have at least one entry track and at least one exit track.

### trainStationNew-inv2

Every signal must be assigned to exactly one track.

### trainStationNew-inv3

The exit tracks must be exactly those tracks with no immediate successor.

### trainStationNew-inv4

The entry tracks must be exactly those tracks with no immediate predecessor.

### trainStationNew-inv5

A track must be a junction exactly when it has at least two immediate predecessors.

### trainStationNew-inv6

Every entry track must have at least one speed signal.

### trainStationNew-inv7

The successor connections must contain no directed cycles, including self-loops.

### trainStationNew-inv8

Every exit track must be reachable from every entry track by following zero or more successor connections.

### trainStationNew-inv9

A track may have a semaphore signal only if at least one of its immediate successors is a junction.

### trainStationNew-inv10

Every track that leads directly into a junction must have at least one semaphore signal.

## trainStationOld

### trainStationOld-inv1

Initially, no signal may be green.

### trainStationOld-inv2

Every signal must be green at least once, either initially or at a later state.

### trainStationOld-inv3

Each train must keep its initial position forever. A train initially outside the station must remain outside forever.

### trainStationOld-inv4

At every state, each track may be occupied by at most one train.

### trainStationOld-inv5

At every step, a train already in the station may stay on its current track; otherwise, it must leave the station if on an exit track, or move to an immediate successor if on any other track. This requirement places no restriction on a train that is currently outside the station.

### trainStationOld-inv6

Every signal must be green infinitely often and non-green infinitely often.

### trainStationOld-inv7

Whenever a train is in the station, it must eventually be outside the station again.

### trainStationOld-inv8

Whenever a train occupies a track with a non-green signal, it must stay on that track through the first state in which the signal becomes green. If the signal never becomes green, the train must stay there forever.

### trainStationOld-inv9

Every train must eventually occupy an entry track and must be outside the station in every preceding state. Occupying an entry track in the initial state is allowed.

### trainStationOld-inv10

At every state, at most one signal on the tracks leading directly into any given junction may be green.

### trainStationOld-inv11

Whenever a train is in the station, it must have occupied an entry track in the current state or in an earlier state.

### trainStationOld-inv13

Once a train is outside the station after having been inside, it must remain outside forever.

### trainStationOld-inv14

Whenever a train leaves a track whose signal is green, that signal must be non-green in the next state.

### trainStationOld-inv15

No train may eventually remain on one particular track forever. A train may remain outside the station forever.

### trainStationOld-inv16

Whenever a train occupies an exit track, it must have occupied an entry track in an earlier or current state and remained continuously inside the station since that state.

### trainStationOld-inv17

Whenever a train is in the station and no other train has been inside at any earlier or current state, that train must eventually occupy an exit track. No train may occupy any exit track before it does.

## trash_fol

### trash_fol-inv1

The trash must be empty.

### trash_fol-inv2

Every file must be in the trash.

### trash_fol-inv3

The trash must contain at least one file.

### trash_fol-inv4

No protected file may be in the trash.

### trash_fol-inv5

Every file must be in the trash, protected, or both.

### trash_fol-inv6

Each file may link directly to at most one file.

### trash_fol-inv7

No file may link directly to a file in the trash.

### trash_fol-inv8

There must be no links between files.

### trash_fol-inv9

Following a link from any file must never lead to a file that itself has an outgoing link.

### trash_fol-inv10

Every file linked to directly by a file in the trash must also be in the trash.

## trash_ltl

### trash_ltl-inv1

Initially, the trash must be empty and no file may be protected.

### trash_ltl-inv2

Initially, no files may exist; in the next state, at least one file must exist.

### trash_ltl-inv3

At every state, at least one file must exist.

### trash_ltl-inv4

At some state, possibly the initial state, the trash must contain at least one file.

### trash_ltl-inv5

At some point, a file that exists in one state must no longer exist in the next state.

### trash_ltl-inv6

Once a file is in the trash, it must remain there forever.

### trash_ltl-inv7

At some state, possibly the initial state, at least one file must be protected.

### trash_ltl-inv8

Whenever a file has an outgoing link, that file must be in the trash in the current state or at some later state.

### trash_ltl-inv9

At every state, no protected file may be in the trash.

### trash_ltl-inv10

The set of protected files must remain exactly the same throughout the entire trace.

### trash_ltl-inv11

At every step, each currently existing unprotected file must be protected in the next state.

### trash_ltl-inv12

Eventually, there must be a file that remains in the trash from that state onward forever.

### trash_ltl-inv13

Whenever a file is in the trash, there must have been an earlier state in which it was not in the trash. That earlier state may precede the file's existence.

### trash_ltl-inv14

Whenever a file is both protected and in the trash, it must no longer be protected in the next state.

### trash_ltl-inv15

Every file that exists at any state must be in the trash in that state or at some later state.

### trash_ltl-inv16

Whenever a file is protected, it must have been protected in every state from the beginning of the trace through the current state.

### trash_ltl-inv17

Every file in the trash must cease to exist in the very next state.

### trash_ltl-inv18

Whenever a file is protected, it must stay protected through the first state at or after that point in which it is in the trash, including that state. If it never enters the trash, it must stay protected forever.

### trash_ltl-inv19

Whenever a file is protected, it must be in the trash in the current state or at some later state, and it must remain protected in every intervening state before then.

### trash_ltl-inv20

Whenever a file is in the trash, there must be a current or past state in which it was unprotected. The file must have been continuously in the trash in every state after that state up to the present.

## trash_rl

### trash_rl-inv1

The trash must be empty.

### trash_rl-inv2

Every file must be in the trash.

### trash_rl-inv3

The trash must contain at least one file.

### trash_rl-inv4

No protected file may be in the trash.

### trash_rl-inv5

Every file must be in the trash, protected, or both.

### trash_rl-inv6

Each file may link directly to at most one file.

### trash_rl-inv7

No file may link directly to a file in the trash.

### trash_rl-inv8

There must be no links between files.

### trash_rl-inv9

Following a link from any file must never lead to a file that itself has an outgoing link.

### trash_rl-inv10

Every file linked to directly by a file in the trash must also be in the trash.
