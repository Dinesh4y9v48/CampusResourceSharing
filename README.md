# CampusResourceSharing
Modern university campuses face a big problem: they aren't using academic resources well.

Students often buy expensive books, tools, and study materials that sit unused for most of the semester, while others can't afford these things and struggle to get them. This research paper looks at a Campus Resource Sharing Platform (CRSP), which is a web-based system that lets students share resources with each other within a university. The study examines how this platform works technically, how students interact with it, and how it can help create a more supportive and collaborative campus environment. The platform uses a RESTful API built with the Spring Boot framework, and it manages data in memory to make sure things run quickly and smoothly. This paper also looks at the ideas behind the sharing economy in education, explains how the platform was built, discusses its social and economic effects, and suggests ways to improve it for better scalability and new features.

Keywords: Resource Sharing, Campus Community, Peer-to-Peer Systems, Spring Boot, Collaborative Economy, Educational Technology, Student Support Systems

#Data Models

*User Entity:* Represents platform participants with unique identifier, name, email address, and campus affiliation. Email serves multiple purposes: authentication, communication channel, and identity verification.

*Resource Entity:* Represents shareable items with identifier, title, category, owner reference, description, and active status. Categories enable classification and filtering. The active status implements soft deletion.

*BorrowRequest Entity:* Captures interactions between borrowers and owners with identifier, resource reference, requester reference, owner reference, message, and status (Pending, Accepted, Rejected).
