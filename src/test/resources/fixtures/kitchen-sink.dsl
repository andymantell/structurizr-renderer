workspace "Streamflix" "Kitchen-sink fixture: an imagined video streaming platform exercising as many renderer features as possible" {

    model {
        viewer = person "Viewer" "A customer who streams films & TV episodes on <multiple> devices at home and on the move"
        admin = person "Content Administrator" "Curates the catalogue, manages licensing windows and regional availability rules across every territory" "Staff"

        group "Third-Party Services" {
            payments = softwareSystem "Global Payment Gateway" "Authorises card payments, handles 3-D Secure challenges and recurring subscription billing" "External"
            email = softwareSystem "Transactional Email Service" "Sends receipts, password resets and watch-list reminders" "External"
        }

        streamflix = softwareSystem "Streamflix" "Subscription video-on-demand platform with personalised recommendations and offline downloads" {
            web = container "Web Application" "Single-page application delivering the browsing & playback experience" "TypeScript and React" "Browser"
            mobile = container "Mobile App" "Native client with offline downloads and Chromecast support" "Kotlin Multiplatform" "Mobile"
            adminConsole = container "Admin Console" "Back-office catalogue management and licensing-window UI" "TypeScript and React" "Window"

            api = container "Streaming API" "Public REST/GraphQL API used by all client applications" "Java and Spring Boot" "API" {
                authComponent = component "Authentication Controller" "Issues & refreshes JWT access tokens; enforces device limits" "Spring MVC Controller"
                catalogComponent = component "Catalogue Service" "Search, browse and continue-watching endpoints" "Spring Bean"
                playbackComponent = component "Playback Service" "Generates signed manifest URLs and enforces concurrent-stream limits" "Spring Bean"
                billingComponent = component "Billing Facade" "Anti-corruption layer in front of the payment gateway" "Spring Bean"

                authComponent -> catalogComponent "Provides authenticated principal to"
                catalogComponent -> playbackComponent "Resolves playable assets through"
                playbackComponent -> billingComponent "Checks entitlement status with"
                billingComponent -> playbackComponent "Returns entitlement verdict to"
            }

            recommender = container "Recommendation Engine" "Trains nightly and serves personalised row rankings with sub-50ms p99 latency" "Python and PyTorch" "Bot"
            worker = container "Encoding Worker" "Transcodes mezzanine files into adaptive-bitrate ladders using the hyperoptimised-multiresolution-transcoding-pipeline" "Go"
            queue = container "Job Queue" "Buffered hand-off of encoding and notification jobs" "Amazon SQS" "Queue"
            db = container "Catalogue Database" "Titles, entitlements, profiles and watch history" "PostgreSQL" "Database"
            cache = container "Session Cache" "Hot session & entitlement lookups" "Redis" "Database"
            assets = container "Asset Store" "Posters, subtitles and encoded video segments" "Amazon S3" "Files"
        }

        viewer -> web "Browses and watches titles using" "HTTPS"
        viewer -> mobile "Watches downloads & casts from"
        admin -> adminConsole "Curates the catalogue using"

        # Bidirectional pair between containers
        web -> api "Sends API requests to" "JSON/HTTPS"
        api -> web "Pushes playback events back to" "Server-Sent Events"
        mobile -> api "Makes API calls to" "JSON/HTTPS"
        adminConsole -> api "Manages titles & licensing windows via"

        # Parallel same-direction pair
        api -> db "Reads catalogue & profile data from" "JDBC"
        api -> db "Writes watch history to" "JDBC"

        api -> cache "Looks up sessions in"
        api -> queue "Enqueues encoding & notification jobs on"
        worker -> queue "Consumes jobs from"

        # Self-relationship
        worker -> worker "Retries failed encodes with exponential backoff"

        worker -> assets "Writes encoded segments to"
        worker -> db "Updates encoding status in"
        recommender -> db "Reads viewing signals from"
        api -> recommender "Fetches personalised rows from" "gRPC" "ML"

        # Bidirectional pair between systems
        api -> payments "Authorises subscriptions using" "HTTPS"
        payments -> api "Delivers payment webhooks to" "HTTPS"
        api -> email "Sends notifications via"

        production = deploymentEnvironment "Production" {
            deploymentNode "Amazon Web Services" {
                tags "Amazon Web Services - Cloud"

                deploymentNode "us-east-1" {
                    tags "Amazon Web Services - Region"

                    dns = infrastructureNode "DNS" "Latency-based routing of viewer traffic" "Route 53" {
                        tags "Amazon Web Services - Route 53"
                    }
                    lb = infrastructureNode "Load Balancer" "Terminates TLS and balances across the API service" "Elastic Load Balancing" {
                        tags "Amazon Web Services - Elastic Load Balancing"
                    }
                    cdn = infrastructureNode "CDN" "Caches manifests and video segments at the edge" "CloudFront" {
                        tags "Amazon Web Services - CloudFront"
                    }

                    deploymentNode "ECS Cluster" "" "Amazon ECS" {
                        tags "Amazon Web Services - Elastic Container Service"
                        apiInstance = containerInstance api
                        recommenderInstance = containerInstance recommender
                    }

                    deploymentNode "Encoding Fleet" "" "EC2 Auto Scaling" "" 4 {
                        tags "Amazon Web Services - EC2 Auto Scaling"
                        workerInstance = containerInstance worker
                    }

                    deploymentNode "Amazon RDS" "" "PostgreSQL 16" {
                        tags "Amazon Web Services - RDS"
                        dbInstance = containerInstance db
                    }

                    deploymentNode "Amazon ElastiCache" "" "Redis" {
                        tags "Amazon Web Services - ElastiCache"
                        cacheInstance = containerInstance cache
                    }

                    deploymentNode "Amazon SQS" {
                        tags "Amazon Web Services - Simple Queue Service SQS"
                        queueInstance = containerInstance queue
                    }

                    deploymentNode "Amazon S3" {
                        tags "Amazon Web Services - Simple Storage Service S3"
                        assetsInstance = containerInstance assets
                    }
                }
            }

            deploymentNode "Viewer's Device" "" "Browser or mobile" {
                deploymentNode "Web Browser" "" "Chrome, Firefox, Safari or Edge" {
                    webInstance = containerInstance web
                }
            }

            dns -> lb "Routes API traffic to"
            dns -> cdn "Routes media traffic to"
            lb -> apiInstance "Forwards requests to" "HTTPS"
            cdn -> assetsInstance "Fetches segments on cache miss from"
        }
    }

    views {
        systemLandscape "Landscape" "Everyone and everything around Streamflix" {
            include *
            autoLayout
        }

        systemContext streamflix "SystemContext" "Streamflix and its neighbours" {
            include *
            autoLayout
        }

        container streamflix "Containers" "The moving parts inside Streamflix" {
            include *
            autoLayout
        }

        component api "ApiComponents" "Inside the Streaming API" {
            include *
            autoLayout
        }

        dynamic streamflix "Playback" "How a viewer starts playback, including the response leg" {
            viewer -> web "Presses play on a title in"
            web -> api "Requests a playback manifest from"
            api -> cache "Checks entitlement & stream count in"
            cache -> api "Returns the cached session to"
            api -> web "Returns a signed manifest URL to"
            autoLayout
        }

        deployment streamflix production "ProductionDeployment" "Streamflix running on AWS" {
            include *
            autoLayout lr
        }

        styles {
            element "Person" {
                shape Person
                background #08427b
                color #ffffff
            }
            element "Staff" {
                background #999999
            }
            element "External" {
                background #8c8496
                color #ffffff
            }
            element "Container" {
                background #438dd5
                color #ffffff
            }
            element "Software System" {
                background #1168bd
                color #ffffff
            }
            element "Browser" {
                shape WebBrowser
            }
            element "Mobile" {
                shape MobileDevicePortrait
            }
            element "Window" {
                shape Window
            }
            element "API" {
                shape Hexagon
            }
            element "Bot" {
                shape Robot
            }
            element "Database" {
                shape Cylinder
            }
            element "Queue" {
                shape Pipe
            }
            element "Files" {
                shape Folder
            }
            element "Component" {
                shape Component
                background #85bbf0
            }
            relationship "ML" {
                routing Curved
            }
        }

        theme https://static.structurizr.com/themes/amazon-web-services-2020.04.30/theme.json
    }
}
