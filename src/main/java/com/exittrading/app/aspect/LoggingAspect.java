package com.exittrading.app.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.util.Arrays;

/**
 * Aspect to centralize logging for service execution.
 * Logs method entry, arguments, exit, and execution time.
 */
@Aspect
@Component
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);

    /**
     * Pointcut that matches all public methods in the service package.
     */
    @Around("execution(* com.exittrading.app.service..*.*(..))")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        // Exclude noisy high-frequency classes
        if (className.equals("KiteSessionManager") || className.equals("DepthStreamService")) {
            return joinPoint.proceed();
        }
        
        String methodName = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();

        log.debug("Enter: {}.{}({})", className, methodName, Arrays.toString(args));

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        try {
            Object result = joinPoint.proceed();
            stopWatch.stop();
            log.debug("Exit: {}.{}() returned in {} ms", className, methodName, stopWatch.getTotalTimeMillis());
            return result;
        } catch (Throwable e) {
            stopWatch.stop();
            log.error("Exception in {}.{}() after {} ms: {}", className, methodName, stopWatch.getTotalTimeMillis(), e.getMessage());
            throw e;
        }
    }
}
