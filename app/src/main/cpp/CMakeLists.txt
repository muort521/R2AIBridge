cmake_minimum_required(VERSION 3.22.1)

project("r2aibridge")

# Add radare2 library directory
set(R2_LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# Create native library
add_library(${CMAKE_PROJECT_NAME} SHARED native-lib.cpp)

# Find log library
find_library(log-lib log)

# Import all radare2 libraries as prebuilt
add_library(r_util SHARED IMPORTED)
set_target_properties(r_util PROPERTIES IMPORTED_LOCATION ${R2_LIB_DIR}/libr_util.so)

add_library(r_core SHARED IMPORTED)
set_target_properties(r_core PROPERTIES IMPORTED_LOCATION ${R2_LIB_DIR}/libr_core.so)

# Link libraries (only r_core needed, it will pull in dependencies)
target_link_libraries(${CMAKE_PROJECT_NAME} 
    ${log-lib}
    r_core
)
