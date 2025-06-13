protected void startService(ServiceArgs serviceArgs) {
    String serviceName = null; // Initialize serviceName for logging and error reporting
    try {
        // --- 1. Initialize Base Guice Injector ---
        BASE_INJECTOR = createBaseInjector();

        // --- 2. Load Core Service Configuration ---
        ServiceConfigurationLoader configLoader = new ServiceConfigurationLoader(BASE_INJECTOR, serviceArgs);
        ServiceConfiguration serviceConfig = configLoader.getCoreConfig();
        serviceName = serviceConfig.getServiceName(); // Extract service name early for logging

        // --- 3. Set Current Service Context ---
        Service service = new Service(serviceName); // Create a Service object with the identified name
        this.service = service; // Store it as a member variable for later access

        // --- 4. Load All Application-Specific Configurations ---
        Map<String, ServiceConfiguration> serviceConfigMap = configLoader.loadServiceConfiguration();

        // --- 5. (TODO) Legacy System Property Setup ---
        // TODO: these two should be phased out - This is a self-correction/note from the AppOps developers.
        // It indicates that these global system properties are older ways of managing
        // configuration context and are intended to be replaced by more robust Guice-based injection.
        System.setProperty("currentProfile", configLoader.getProfileName());
        System.setProperty("baseUrl", serviceConfig.serviceUrl());

        // --- 6. Initialize Main Service Guice Injector ---
        SERVICE_INJECTOR = createServiceInjector(BASE_INJECTOR, serviceConfigMap, serviceConfig);

        // --- 7. Set Current Deployment Context for Injection ---
        CurrentDeploymentProvider currentDeployment = SERVICE_INJECTOR.getInstance(CurrentDeploymentProvider.class);
        currentDeployment.setCurrentServiceConfig(serviceConfig); // Make the core service config available via provider

        // --- 8. Prepare for Web Service Deployment (if applicable) ---
        // This is typically for integrating with Servlet containers like Jetty
        ServiceServletContextListener.setInjector(SERVICE_INJECTOR);

        // --- 9. Determine and Execute Run Mode (Jetty vs. No-Jetty) ---
        if (serviceArgs.getJettyRunMode()) {
            // --- 9a. Jetty (Web Service) Mode ---
            ServiceJettyLauncher appLauncher = SERVICE_INJECTOR.getInstance(ServiceJettyLauncher.class);
            // Configure Jetty server with port and other settings
            appLauncher.getJettyContainer().readyServer(serviceConfig.getWebConfig().getPort(),
                    serviceConfig.getJettyConfig());
            // Deploy the web application context (e.g., servlets, filters)
            appLauncher.deployServiceDirect(serviceConfig);
            // Initialize other services (configurations, modules, initializers)
            // This happens *before* starting Jetty, ensuring everything is ready
            initializeServices(serviceConfigMap, serviceConfig, configLoader.getProfileName(),
                    configLoader.getProfileRoot());
            // Start the Jetty server (makes the service accessible via HTTP)
            appLauncher.startService();
            // Block the main thread, keeping the Jetty server running
            appLauncher.joinService();
        } else {
            // --- 9b. No-Jetty (Background/Worker) Mode ---
            // If Jetty is not run, the service might be a background worker,
            // a message queue listener, or a command-line tool.
            // Still, initialize all other services and configurations.
            initializeServices(serviceConfigMap, serviceConfig, configLoader.getProfileName(),
                    configLoader.getProfileRoot());
        }

        // --- 10. Log Success ---
        logger.log(Level.ALL, "Service started successfully for " + serviceConfig.getServiceName());

    } catch (Exception e) {
        // --- 11. Handle and Log Initialization Failure ---
        logger.log(Level.ALL, "Service initialization failed for " + serviceName, e);
        throw new AppEntryPointException(e); // Re-throw as a specific AppOps entry point exception
    } finally {
        // --- 12. Final Logging on Exit (success or failure) ---
        // This log might be misleading if the service is meant to run indefinitely.
        // It might be more appropriate for a short-lived command-line tool.
        // For a long-running web service, 'Exiting' would typically only happen on shutdown.
        logger.log(Level.ALL, "Exiting app with Name " + serviceName);
    }
}