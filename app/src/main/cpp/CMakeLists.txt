cmake_minimum_required(VERSION 3.22.1)

# Keep every native target produced by this project compatible with Android
# devices using 16 KB memory pages. Prebuilt JNI libraries are audited
# separately because linker flags cannot rewrite their ELF program headers.
add_link_options(
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")

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
