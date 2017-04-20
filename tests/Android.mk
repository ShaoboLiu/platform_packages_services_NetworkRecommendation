LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_STATIC_JAVA_LIBRARIES := \
    android-support-test \
    guava \
    jsr305 \
    mockito-target

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := NetworkRecommendationTests
LOCAL_CERTIFICATE := platform

LOCAL_INSTRUMENTATION_FOR := NetworkRecommendation

include $(BUILD_PACKAGE)
