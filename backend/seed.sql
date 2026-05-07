-- ================================
-- TRUNCATE
-- ================================
TRUNCATE TABLE job_requirement, job_offer RESTART IDENTITY CASCADE;

-- ================================
-- JOB OFFERS
-- ================================
INSERT INTO job_offer (id, title, description, location, employment_type, min_salary, max_salary, job_status, ref_number) VALUES
(gen_random_uuid(), 'Backend Developer',          'Build and maintain microservices using Java and Spring Boot',               'Remote',    'FULL_TIME',  2500, 4000, 'PUBLISHED', 'REF001'),
(gen_random_uuid(), 'Frontend Developer',         'Develop responsive UIs with Angular and React',                            'In Person', 'FULL_TIME',  2000, 3500, 'PUBLISHED', 'REF002'),
(gen_random_uuid(), 'DevOps Engineer',            'Manage CI/CD pipelines and cloud infrastructure on AWS',                   'Remote',    'FULL_TIME',  3000, 5000, 'PUBLISHED', 'REF003'),
(gen_random_uuid(), 'Data Engineer',              'Design and maintain data pipelines using Python and Apache Spark',          'In Person', 'FULL_TIME',  2800, 4500, 'PUBLISHED', 'REF004'),
(gen_random_uuid(), 'Mobile Developer',           'Develop cross-platform mobile applications with Flutter',                  'Remote',    'FULL_TIME',  2200, 3800, 'PUBLISHED', 'REF005'),
(gen_random_uuid(), 'QA Engineer',                'Write and execute automated test suites using Selenium and JUnit',         'In Person', 'FULL_TIME',  1800, 3000, 'PUBLISHED', 'REF006'),
(gen_random_uuid(), 'Cloud Architect',            'Design scalable and resilient cloud solutions on AWS and GCP',             'Remote',    'CONTRACT',   4000, 7000, 'PUBLISHED', 'REF007'),
(gen_random_uuid(), 'Machine Learning Engineer',  'Build, train and deploy ML models for production systems',                 'Remote',    'FULL_TIME',  3500, 6000, 'PUBLISHED', 'REF008'),
(gen_random_uuid(), 'Scrum Master',               'Facilitate agile ceremonies and remove team impediments',                  'In Person', 'FULL_TIME',  2500, 4000, 'PUBLISHED', 'REF009'),
(gen_random_uuid(), 'Full Stack Developer',       'Work across backend Node.js and frontend Vue.js layers',                   'Remote',    'FULL_TIME',  2500, 4200, 'PUBLISHED', 'REF010'),
(gen_random_uuid(), 'Security Engineer',          'Identify vulnerabilities and harden application and network security',     'In Person', 'FULL_TIME',  3200, 5500, 'PUBLISHED', 'REF011'),
(gen_random_uuid(), 'Database Administrator',     'Administer and optimize PostgreSQL and Oracle databases',                  'In Person', 'FULL_TIME',  2500, 4000, 'PUBLISHED', 'REF012'),
(gen_random_uuid(), 'Tech Lead',                  'Lead a team of developers and drive key technical decisions',              'In Person', 'FULL_TIME',  4000, 6500, 'PUBLISHED', 'REF013'),
(gen_random_uuid(), 'Business Analyst',           'Gather requirements and bridge business and technical teams',              'Remote',    'FULL_TIME',  2000, 3500, 'PUBLISHED', 'REF014'),
(gen_random_uuid(), 'Embedded Systems Engineer',  'Develop firmware for IoT devices using C and C++',                        'In Person', 'FULL_TIME',  2500, 4000, 'PUBLISHED', 'REF015'),
(gen_random_uuid(), 'Data Scientist',             'Analyze large datasets and build predictive models using Python',          'Remote',    'FULL_TIME',  3000, 5500, 'DRAFT',     'REF016'),
(gen_random_uuid(), 'UI/UX Designer',             'Design intuitive interfaces and conduct usability testing',                'Remote',    'FULL_TIME',  1800, 3200, 'PUBLISHED', 'REF017'),
(gen_random_uuid(), 'IT Support Engineer',        'Provide L2/L3 technical support and maintain internal IT infrastructure',  'In Person', 'FULL_TIME',  1500, 2500, 'PUBLISHED', 'REF018'),
(gen_random_uuid(), 'Product Manager',            'Define product vision and roadmap in collaboration with stakeholders',     'Remote',    'FULL_TIME',  3500, 6000, 'PUBLISHED', 'REF019'),
(gen_random_uuid(), 'Internship - Java Developer','6-month internship focused on backend Java development',                   'In Person', 'INTERNSHIP',  600, 1000, 'PUBLISHED', 'REF020');


-- ================================
-- REQUIREMENTS
-- (each references its job offer via subquery on ref_number)
-- ================================

-- REF001 – Backend Developer
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',      'Java and Spring Boot',           3, 7, 0.40, (SELECT id FROM job_offer WHERE ref_number = 'REF001')),
(gen_random_uuid(), 'SKILL',      'REST API design',                2, 5, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF001')),
(gen_random_uuid(), 'SKILL',      'Docker and Kubernetes',          1, 4, 0.20, (SELECT id FROM job_offer WHERE ref_number = 'REF001')),
(gen_random_uuid(), 'EXPERIENCE', 'Microservices architecture',     2, 5, 0.15, (SELECT id FROM job_offer WHERE ref_number = 'REF001'));

-- REF002 – Frontend Developer
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',      'Angular or React',               2, 6, 0.40, (SELECT id FROM job_offer WHERE ref_number = 'REF002')),
(gen_random_uuid(), 'SKILL',      'TypeScript',                     2, 5, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF002')),
(gen_random_uuid(), 'SKILL',      'CSS and Tailwind',               1, 3, 0.20, (SELECT id FROM job_offer WHERE ref_number = 'REF002')),
(gen_random_uuid(), 'EXPERIENCE', 'Responsive web design',          1, 4, 0.10, (SELECT id FROM job_offer WHERE ref_number = 'REF002'));

-- REF003 – DevOps Engineer
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',         'CI/CD with Jenkins or GitLab', 2, 6, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF003')),
(gen_random_uuid(), 'SKILL',         'Docker and Kubernetes',        2, 5, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF003')),
(gen_random_uuid(), 'SKILL',         'Terraform and Ansible',        1, 4, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF003')),
(gen_random_uuid(), 'CERTIFICATION', 'AWS Certified DevOps',         0, 0, 0.15, (SELECT id FROM job_offer WHERE ref_number = 'REF003'));

-- REF004 – Data Engineer
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',      'Python',                         3, 7, 0.35, (SELECT id FROM job_offer WHERE ref_number = 'REF004')),
(gen_random_uuid(), 'SKILL',      'Apache Spark',                   2, 5, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF004')),
(gen_random_uuid(), 'SKILL',      'SQL and data modeling',          2, 5, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF004')),
(gen_random_uuid(), 'EXPERIENCE', 'ETL pipeline development',       2, 5, 0.10, (SELECT id FROM job_offer WHERE ref_number = 'REF004'));

-- REF005 – Mobile Developer
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',      'Flutter and Dart',               2, 5, 0.40, (SELECT id FROM job_offer WHERE ref_number = 'REF005')),
(gen_random_uuid(), 'SKILL',      'Android or iOS development',     2, 5, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF005')),
(gen_random_uuid(), 'SKILL',      'REST API integration',           1, 4, 0.20, (SELECT id FROM job_offer WHERE ref_number = 'REF005')),
(gen_random_uuid(), 'EXPERIENCE', 'Publishing apps to Play Store',  1, 3, 0.10, (SELECT id FROM job_offer WHERE ref_number = 'REF005'));

-- REF006 – QA Engineer
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',      'Selenium and test automation',   2, 5, 0.35, (SELECT id FROM job_offer WHERE ref_number = 'REF006')),
(gen_random_uuid(), 'SKILL',      'JUnit and TestNG',               2, 5, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF006')),
(gen_random_uuid(), 'SKILL',      'Performance testing with JMeter',1, 4, 0.20, (SELECT id FROM job_offer WHERE ref_number = 'REF006')),
(gen_random_uuid(), 'EXPERIENCE', 'Agile QA processes',             1, 3, 0.15, (SELECT id FROM job_offer WHERE ref_number = 'REF006'));

-- REF007 – Cloud Architect
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',         'AWS and GCP services',           4, 10, 0.35, (SELECT id FROM job_offer WHERE ref_number = 'REF007')),
(gen_random_uuid(), 'SKILL',         'Infrastructure as Code',         3, 8,  0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF007')),
(gen_random_uuid(), 'CERTIFICATION', 'AWS Solutions Architect Pro',    0, 0,  0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF007')),
(gen_random_uuid(), 'EXPERIENCE',    'Multi-region cloud deployments',  3, 8,  0.10, (SELECT id FROM job_offer WHERE ref_number = 'REF007'));

-- REF008 – Machine Learning Engineer
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',     'Python and scikit-learn',         3, 7, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF008')),
(gen_random_uuid(), 'SKILL',     'TensorFlow or PyTorch',           2, 6, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF008')),
(gen_random_uuid(), 'SKILL',     'MLOps and model deployment',      2, 5, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF008')),
(gen_random_uuid(), 'EDUCATION', 'Masters in AI or Data Science',   0, 0, 0.15, (SELECT id FROM job_offer WHERE ref_number = 'REF008'));

-- REF009 – Scrum Master
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'EXPERIENCE',    'Scrum Master role',              2, 6, 0.35, (SELECT id FROM job_offer WHERE ref_number = 'REF009')),
(gen_random_uuid(), 'CERTIFICATION', 'Certified Scrum Master (CSM)',   0, 0, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF009')),
(gen_random_uuid(), 'SKILL',         'Jira and Confluence',            2, 5, 0.20, (SELECT id FROM job_offer WHERE ref_number = 'REF009')),
(gen_random_uuid(), 'SKILL',         'Conflict resolution',            1, 4, 0.15, (SELECT id FROM job_offer WHERE ref_number = 'REF009'));

-- REF010 – Full Stack Developer
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',      'Node.js and Express',             2, 6, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF010')),
(gen_random_uuid(), 'SKILL',      'Vue.js or React',                 2, 5, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF010')),
(gen_random_uuid(), 'SKILL',      'PostgreSQL and MongoDB',          2, 5, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF010')),
(gen_random_uuid(), 'EXPERIENCE', 'Full lifecycle web development',  2, 5, 0.15, (SELECT id FROM job_offer WHERE ref_number = 'REF010'));

-- REF011 – Security Engineer
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',         'Penetration testing',           3, 7, 0.35, (SELECT id FROM job_offer WHERE ref_number = 'REF011')),
(gen_random_uuid(), 'SKILL',         'SIEM tools and log analysis',   2, 5, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF011')),
(gen_random_uuid(), 'CERTIFICATION', 'CEH or OSCP',                   0, 0, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF011')),
(gen_random_uuid(), 'EXPERIENCE',    'Security audits and reporting',  2, 5, 0.15, (SELECT id FROM job_offer WHERE ref_number = 'REF011'));

-- REF012 – Database Administrator
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',      'PostgreSQL administration',       3, 8, 0.35, (SELECT id FROM job_offer WHERE ref_number = 'REF012')),
(gen_random_uuid(), 'SKILL',      'Query optimization and indexing', 2, 6, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF012')),
(gen_random_uuid(), 'SKILL',      'Backup and disaster recovery',    2, 5, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF012')),
(gen_random_uuid(), 'EXPERIENCE', 'High availability database setup',2, 5, 0.10, (SELECT id FROM job_offer WHERE ref_number = 'REF012'));

-- REF013 – Tech Lead
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'EXPERIENCE', 'Technical team leadership',       4, 10, 0.35, (SELECT id FROM job_offer WHERE ref_number = 'REF013')),
(gen_random_uuid(), 'SKILL',      'System design and architecture',  4, 10, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF013')),
(gen_random_uuid(), 'SKILL',      'Code review and mentoring',       3, 8,  0.20, (SELECT id FROM job_offer WHERE ref_number = 'REF013')),
(gen_random_uuid(), 'SKILL',      'Agile and DevOps practices',      3, 7,  0.15, (SELECT id FROM job_offer WHERE ref_number = 'REF013'));

-- REF014 – Business Analyst
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',      'Requirements gathering',          2, 6, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF014')),
(gen_random_uuid(), 'SKILL',      'UML and process modeling',        2, 5, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF014')),
(gen_random_uuid(), 'SKILL',      'Jira and Confluence',             1, 4, 0.20, (SELECT id FROM job_offer WHERE ref_number = 'REF014')),
(gen_random_uuid(), 'EXPERIENCE', 'Agile project delivery',          2, 5, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF014'));

-- REF015 – Embedded Systems Engineer
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',      'C and C++ programming',           3, 8, 0.40, (SELECT id FROM job_offer WHERE ref_number = 'REF015')),
(gen_random_uuid(), 'SKILL',      'RTOS and bare-metal development', 2, 6, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF015')),
(gen_random_uuid(), 'SKILL',      'UART, SPI and I2C protocols',     2, 5, 0.20, (SELECT id FROM job_offer WHERE ref_number = 'REF015')),
(gen_random_uuid(), 'EXPERIENCE', 'IoT hardware integration',        1, 4, 0.10, (SELECT id FROM job_offer WHERE ref_number = 'REF015'));

-- REF016 – Data Scientist
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',     'Python and R',                     3, 7, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF016')),
(gen_random_uuid(), 'SKILL',     'Statistical modeling',             2, 6, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF016')),
(gen_random_uuid(), 'SKILL',     'Data visualization with Tableau',  2, 5, 0.20, (SELECT id FROM job_offer WHERE ref_number = 'REF016')),
(gen_random_uuid(), 'EDUCATION', 'Masters in Statistics or CS',      0, 0, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF016'));

-- REF017 – UI/UX Designer
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',      'Figma and Adobe XD',              2, 6, 0.35, (SELECT id FROM job_offer WHERE ref_number = 'REF017')),
(gen_random_uuid(), 'SKILL',      'User research and testing',       2, 5, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF017')),
(gen_random_uuid(), 'SKILL',      'Design systems and prototyping',  1, 4, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF017')),
(gen_random_uuid(), 'EXPERIENCE', 'Mobile and web UI design',        1, 4, 0.10, (SELECT id FROM job_offer WHERE ref_number = 'REF017'));

-- REF018 – IT Support Engineer
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',         'Windows and Linux administration', 2, 5, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF018')),
(gen_random_uuid(), 'SKILL',         'Networking and TCP/IP',            2, 5, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF018')),
(gen_random_uuid(), 'SKILL',         'Ticketing systems (ServiceNow)',   1, 4, 0.20, (SELECT id FROM job_offer WHERE ref_number = 'REF018')),
(gen_random_uuid(), 'CERTIFICATION', 'CompTIA A+ or Network+',           0, 0, 0.20, (SELECT id FROM job_offer WHERE ref_number = 'REF018'));

-- REF019 – Product Manager
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'EXPERIENCE', 'Product management lifecycle',    3, 8, 0.35, (SELECT id FROM job_offer WHERE ref_number = 'REF019')),
(gen_random_uuid(), 'SKILL',      'Roadmap planning and OKRs',       3, 7, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF019')),
(gen_random_uuid(), 'SKILL',      'Stakeholder communication',       2, 6, 0.25, (SELECT id FROM job_offer WHERE ref_number = 'REF019')),
(gen_random_uuid(), 'SKILL',      'Data-driven decision making',     2, 5, 0.15, (SELECT id FROM job_offer WHERE ref_number = 'REF019'));

-- REF020 – Internship Java Developer
INSERT INTO job_requirement (id, category, description, min_years, max_years, weight, job_offer_id) VALUES
(gen_random_uuid(), 'SKILL',     'Core Java basics',                 0, 1, 0.40, (SELECT id FROM job_offer WHERE ref_number = 'REF020')),
(gen_random_uuid(), 'SKILL',     'Spring Boot fundamentals',         0, 1, 0.30, (SELECT id FROM job_offer WHERE ref_number = 'REF020')),
(gen_random_uuid(), 'EDUCATION', 'Engineering degree in progress',   0, 0, 0.20, (SELECT id FROM job_offer WHERE ref_number = 'REF020')),
(gen_random_uuid(), 'SKILL',     'Git version control',              0, 1, 0.10, (SELECT id FROM job_offer WHERE ref_number = 'REF020'));