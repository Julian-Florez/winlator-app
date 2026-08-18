cmake_minimum_required(VERSION 3.22.1)

if(NOT DEFINED WINLATOR_APP_CACHE_DIR)
    set(WINLATOR_APP_CACHE_DIR "/data/data/com.winlator/cache")
endif()
if(NOT DEFINED WINLATOR_VORTEK_SERVER_PATH)
    set(WINLATOR_VORTEK_SERVER_PATH "/data/data/com.winlator/files/rootfs/tmp/.vortek/V0")
endif()
if(NOT DEFINED WINLATOR_X11_SERVER_PATH)
    set(WINLATOR_X11_SERVER_PATH "/data/data/com.winlator/files/rootfs/tmp/.X11-unix/X0")
endif()

add_compile_definitions(
    WINLATOR_APP_CACHE_DIR=\"${WINLATOR_APP_CACHE_DIR}\"
    WINLATOR_VORTEK_SERVER_PATH=\"${WINLATOR_VORTEK_SERVER_PATH}\"
    WINLATOR_X11_SERVER_PATH=\"${WINLATOR_X11_SERVER_PATH}\"
)

add_subdirectory(winlator)
add_subdirectory(vortekrenderer)
add_subdirectory(virglrenderer)
add_subdirectory(midihandler)
add_subdirectory(libadrenotools)
add_subdirectory(gladiorenderer)
