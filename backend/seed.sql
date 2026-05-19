BEGIN;

DELETE FROM job_requirement;
DELETE FROM job_offer;

INSERT INTO job_offer (
    id,
    description,
    employment_type,
    experience_weight,
    hired_count,
    job_status,
    location,
    max_salary,
    min_salary,
    openings,
    ref_number,
    semantic_weight,
    seniority_weight,
    skills_weight,
    title,
    work_arrangement
)
VALUES

-- =========================
-- PUBLISHED (30)
-- =========================

(gen_random_uuid(),'Build scalable Java Spring Boot APIs and microservices.','FULL_TIME',0.20,0,'PUBLISHED','Tunis',4500,2800,2,'JOB-06001',0.25,0.10,0.45,'Backend Java Developer','HYBRID'),
(gen_random_uuid(),'Develop Angular enterprise web applications.','FULL_TIME',0.20,0,'PUBLISHED','Sousse',3800,2200,3,'JOB-06002',0.20,0.10,0.50,'Frontend Angular Developer','HYBRID'),
(gen_random_uuid(),'Manage CI/CD pipelines and Kubernetes clusters.','FULL_TIME',0.25,0,'PUBLISHED','Remote',6500,4000,2,'JOB-06003',0.15,0.10,0.50,'DevOps Engineer','REMOTE'),
(gen_random_uuid(),'Create dashboards and BI reports.','FULL_TIME',0.20,0,'PUBLISHED','Nabeul',3400,2000,2,'JOB-06004',0.20,0.15,0.45,'Data Analyst','ON_SITE'),
(gen_random_uuid(),'Assist dev team in coding and testing.','INTERNSHIP',0.10,0,'PUBLISHED','Hammamet',1200,700,4,'JOB-06005',0.20,0.30,0.40,'Software Engineering Intern','ON_SITE'),
(gen_random_uuid(),'Test applications manually and automatically.','FULL_TIME',0.25,0,'PUBLISHED','Tunis',3200,2200,2,'JOB-06006',0.15,0.15,0.45,'QA Engineer','HYBRID'),
(gen_random_uuid(),'Monitor threats and security incidents.','FULL_TIME',0.25,0,'PUBLISHED','Tunis',5500,3500,2,'JOB-06007',0.10,0.15,0.50,'Cybersecurity Analyst','ON_SITE'),
(gen_random_uuid(),'Design modern mobile and web interfaces.','FULL_TIME',0.20,0,'PUBLISHED','Sfax',3900,2200,1,'JOB-06008',0.10,0.15,0.55,'UI UX Designer','HYBRID'),
(gen_random_uuid(),'Deploy AWS infrastructure and cloud services.','FULL_TIME',0.25,0,'PUBLISHED','Remote',7000,4200,2,'JOB-06009',0.15,0.10,0.50,'Cloud Engineer','REMOTE'),
(gen_random_uuid(),'Provide IT support and troubleshooting.','FULL_TIME',0.30,0,'PUBLISHED','Nabeul',2500,1500,3,'JOB-06010',0.10,0.20,0.40,'IT Support Specialist','ON_SITE'),
(gen_random_uuid(),'Build machine learning pipelines and AI APIs.','FULL_TIME',0.20,0,'PUBLISHED','Remote',8500,5000,2,'JOB-06011',0.15,0.10,0.55,'AI Engineer','REMOTE'),
(gen_random_uuid(),'Develop Flutter mobile apps.','FULL_TIME',0.20,0,'PUBLISHED','Tunis',4200,2600,2,'JOB-06012',0.20,0.10,0.50,'Flutter Developer','HYBRID'),
(gen_random_uuid(),'Analyze business needs and write specs.','FULL_TIME',0.30,0,'PUBLISHED','Sousse',4200,2800,1,'JOB-06013',0.15,0.15,0.40,'Business Analyst','ON_SITE'),
(gen_random_uuid(),'Manage Scrum ceremonies and agile delivery.','FULL_TIME',0.30,0,'PUBLISHED','Tunis',5000,3200,1,'JOB-06014',0.15,0.20,0.35,'Scrum Master','HYBRID'),
(gen_random_uuid(),'Develop Node.js APIs and services.','FULL_TIME',0.25,0,'PUBLISHED','Remote',5200,3000,2,'JOB-06015',0.15,0.10,0.50,'Node.js Developer','REMOTE'),
(gen_random_uuid(),'Perform penetration testing and audits.','FULL_TIME',0.25,0,'PUBLISHED','Tunis',6000,3800,2,'JOB-06016',0.10,0.15,0.50,'Security Engineer','ON_SITE'),
(gen_random_uuid(),'Administer PostgreSQL databases and backups.','FULL_TIME',0.30,0,'PUBLISHED','Remote',5800,3500,1,'JOB-06017',0.10,0.15,0.45,'Database Administrator','REMOTE'),
(gen_random_uuid(),'Lead software engineering teams.','FULL_TIME',0.35,0,'PUBLISHED','Tunis',9000,5500,1,'JOB-06018',0.10,0.20,0.35,'Engineering Manager','HYBRID'),
(gen_random_uuid(),'Gather requirements and coordinate projects.','FULL_TIME',0.30,0,'PUBLISHED','Nabeul',4200,2600,1,'JOB-06019',0.15,0.15,0.40,'Project Coordinator','ON_SITE'),
(gen_random_uuid(),'Build embedded systems software.','FULL_TIME',0.25,0,'PUBLISHED','Sfax',5000,3000,2,'JOB-06020',0.15,0.10,0.50,'Embedded Systems Engineer','ON_SITE'),
(gen_random_uuid(),'Create ETL pipelines and data warehouses.','FULL_TIME',0.25,0,'PUBLISHED','Remote',6500,4000,2,'JOB-06021',0.15,0.10,0.50,'Data Engineer','REMOTE'),
(gen_random_uuid(),'Build e-commerce websites using Laravel.','FULL_TIME',0.20,0,'PUBLISHED','Tunis',4200,2500,2,'JOB-06022',0.20,0.10,0.50,'Laravel Developer','HYBRID'),
(gen_random_uuid(),'Develop React modern SPAs.','FULL_TIME',0.20,0,'PUBLISHED','Remote',5000,3000,2,'JOB-06023',0.20,0.10,0.50,'React Developer','REMOTE'),
(gen_random_uuid(),'Design APIs and system integrations.','FULL_TIME',0.30,0,'PUBLISHED','Tunis',6200,3800,1,'JOB-06024',0.15,0.10,0.45,'Solutions Architect','HYBRID'),
(gen_random_uuid(),'Analyze security logs and SIEM alerts.','FULL_TIME',0.25,0,'PUBLISHED','Remote',6200,3900,2,'JOB-06025',0.10,0.15,0.50,'SOC Analyst','REMOTE'),
(gen_random_uuid(),'Support ERP implementation projects.','FULL_TIME',0.30,0,'PUBLISHED','Tunis',4800,3000,2,'JOB-06026',0.15,0.15,0.40,'ERP Consultant','ON_SITE'),
(gen_random_uuid(),'Manage products and roadmap priorities.','FULL_TIME',0.30,0,'PUBLISHED','Remote',7500,4500,1,'JOB-06027',0.15,0.20,0.35,'Product Manager','REMOTE'),
(gen_random_uuid(),'Write automation scripts and infra tools.','FULL_TIME',0.25,0,'PUBLISHED','Sousse',4800,2900,2,'JOB-06028',0.15,0.10,0.50,'Automation Engineer','HYBRID'),
(gen_random_uuid(),'Create graphic branding and assets.','FULL_TIME',0.15,0,'PUBLISHED','Nabeul',2800,1800,1,'JOB-06029',0.10,0.15,0.60,'Graphic Designer','ON_SITE'),
(gen_random_uuid(),'Junior Java developer entry-level role.','FULL_TIME',0.10,0,'PUBLISHED','Tunis',2500,1700,3,'JOB-06030',0.20,0.30,0.40,'Junior Java Developer','HYBRID'),

-- =========================
-- DRAFT (5)
-- =========================

(gen_random_uuid(),'Backend architecture for fintech system.','FULL_TIME',0.20,0,'DRAFT','Tunis',6000,4000,2,'JOB-06031',0.25,0.10,0.45,'Backend Architect','HYBRID'),
(gen_random_uuid(),'Mobile app under development.','FULL_TIME',0.20,0,'DRAFT','Sousse',3500,2200,3,'JOB-06032',0.20,0.10,0.50,'Mobile Developer','HYBRID'),
(gen_random_uuid(),'Data pipeline design for analytics platform.','FULL_TIME',0.25,0,'DRAFT','Remote',7000,4500,2,'JOB-06033',0.15,0.10,0.50,'Senior Data Engineer','REMOTE'),
(gen_random_uuid(),'AI model integration not finalized.','FULL_TIME',0.20,0,'DRAFT','Tunis',9000,5000,2,'JOB-06034',0.15,0.10,0.55,'Senior AI Engineer','REMOTE'),
(gen_random_uuid(),'Security monitoring system planning phase.','FULL_TIME',0.25,0,'DRAFT','Nabeul',5500,3500,2,'JOB-06035',0.10,0.15,0.50,'Security Analyst','ON_SITE'),

-- =========================
-- CLOSED (5)
-- =========================

(gen_random_uuid(),'Old backend role filled.','FULL_TIME',0.20,2,'CLOSED','Tunis',4500,2800,2,'JOB-06036',0.25,0.10,0.45,'Java Developer','HYBRID'),
(gen_random_uuid(),'Frontend role completed hiring cycle.','FULL_TIME',0.20,3,'CLOSED','Sousse',3800,2200,3,'JOB-06037',0.20,0.10,0.50,'Angular Developer','HYBRID'),
(gen_random_uuid(),'DevOps role completed.','FULL_TIME',0.25,2,'CLOSED','Remote',6500,4000,2,'JOB-06038',0.15,0.10,0.50,'DevOps Engineer','REMOTE'),
(gen_random_uuid(),'Data Analyst role filled.','FULL_TIME',0.20,2,'CLOSED','Nabeul',3400,2000,2,'JOB-06039',0.20,0.15,0.45,'Data Analyst','ON_SITE'),
(gen_random_uuid(),'Internship cycle completed.','INTERNSHIP',0.10,4,'CLOSED','Hammamet',1200,700,4,'JOB-06040',0.20,0.30,0.40,'Software Intern','ON_SITE');

-- ============================================================
-- REQUIREMENTS
-- ============================================================

INSERT INTO job_requirement
(id,category,degree_level,description,enrollment_type,language_level,max_years,min_years,skill_level,weight,job_offer_id)
SELECT gen_random_uuid(),'SKILL',NULL,'Java',NULL,NULL,NULL,NULL,'ADVANCED',0.35,id
FROM job_offer WHERE ref_number='JOB-06001';

INSERT INTO job_requirement
SELECT gen_random_uuid(),'SKILL',NULL,'Spring Boot',NULL,NULL,NULL,NULL,'ADVANCED',0.35,id
FROM job_offer WHERE ref_number='JOB-06001';

INSERT INTO job_requirement
SELECT gen_random_uuid(),'LANGUAGE',NULL,'English',NULL,'B2',NULL,NULL,NULL,0.20,id
FROM job_offer WHERE ref_number='JOB-06001';

INSERT INTO job_requirement
SELECT gen_random_uuid(),'SKILL',NULL,'Angular',NULL,NULL,NULL,NULL,'ADVANCED',0.40,id
FROM job_offer WHERE ref_number='JOB-06002';

INSERT INTO job_requirement
SELECT gen_random_uuid(),'SKILL',NULL,'Docker',NULL,NULL,NULL,NULL,'ADVANCED',0.40,id
FROM job_offer WHERE ref_number='JOB-06003';

INSERT INTO job_requirement
SELECT gen_random_uuid(),'SKILL',NULL,'SQL',NULL,NULL,NULL,NULL,'ADVANCED',0.40,id
FROM job_offer WHERE ref_number='JOB-06004';

INSERT INTO job_requirement
SELECT gen_random_uuid(),'EDUCATION','LICENCE_BACHELOR','Computer Science','STUDENT',NULL,NULL,NULL,NULL,0.40,id
FROM job_offer WHERE ref_number='JOB-06005';

INSERT INTO job_requirement
SELECT gen_random_uuid(),'SKILL',NULL,'Python',NULL,NULL,NULL,NULL,'ADVANCED',0.40,id
FROM job_offer WHERE ref_number='JOB-06011';

INSERT INTO job_requirement
SELECT gen_random_uuid(),'SKILL',NULL,'Flutter',NULL,NULL,NULL,NULL,'ADVANCED',0.40,id
FROM job_offer WHERE ref_number='JOB-06012';

INSERT INTO job_requirement
SELECT gen_random_uuid(),'SKILL',NULL,'React',NULL,NULL,NULL,NULL,'ADVANCED',0.40,id
FROM job_offer WHERE ref_number='JOB-06023';

INSERT INTO job_requirement
SELECT gen_random_uuid(),'SKILL',NULL,'Laravel',NULL,NULL,NULL,NULL,'ADVANCED',0.40,id
FROM job_offer WHERE ref_number='JOB-06022';

INSERT INTO job_requirement
SELECT gen_random_uuid(),'SKILL',NULL,'Java',NULL,NULL,NULL,NULL,'INTERMEDIATE',0.40,id
FROM job_offer WHERE ref_number='JOB-06030';

COMMIT;

SELECT ref_number,title,job_status,location
FROM job_offer
ORDER BY ref_number;