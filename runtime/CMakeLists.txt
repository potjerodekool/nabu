cmake_minimum_required(VERSION 3.10)
project(nabu_runtime C)

set(CMAKE_C_STANDARD 11)

add_library(nabu_runtime SHARED nabu_runtime.c)

if(UNIX AND NOT APPLE)
    target_link_libraries(nabu_runtime pthread gcc_s)
    set_target_properties(nabu_runtime PROPERTIES
        INSTALL_RPATH "$ORIGIN"
    )
endif()

if(APPLE)
    target_link_libraries(nabu_runtime pthread)
endif()

if(WIN32)
    target_link_libraries(nabu_runtime kernel32)
endif()

set_target_properties(nabu_runtime PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    PUBLIC_HEADER nabu_runtime.h
)

install(TARGETS nabu_runtime
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
    RUNTIME DESTINATION bin
    PUBLIC_HEADER DESTINATION include
)
