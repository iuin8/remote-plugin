#!/bin/bash
# 自动根据.gitmodules文件内容调整git子模块路径
# 支持带branch分支的子模块配置
# 兼容版本：支持bash 3.x（macOS默认版本）
# 修复版本：解决Git版本检测错误、git仓库检测问题，以及bash 3.x兼容性问题
# 使用方法:
# 1. 手动编辑.gitmodules文件，修改子模块的path配置
# 2. 运行此脚本: ./auto-move-submodules-legacy.sh

set -e

# 配置常量
REQUIRED_GIT_VERSION="2.13.0"
DEFAULT_BRANCH="master"
GIT_MODULES_FILE=".gitmodules"
BACKUP_SUFFIX=".bak"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 全局变量声明（使用普通数组模拟关联数组，兼容bash 3.x）
submodule_map=()
new_submodule_urls=()

# 日志函数
log_info() {
    echo -e "${GREEN}$1${NC}"
}

log_warn() {
    echo -e "${YELLOW}$1${NC}"
}

log_error() {
    echo -e "${RED}$1${NC}"
}

log_normal() {
    echo "$1"
}

# 版本比较函数 - 修复版本比较逻辑
version_ge() {
    # 正确的版本比较：如果版本1 >= 版本2，返回0（true）
    [ "$(printf '%s\n' "$2" "$1" | sort -V | head -n 1)" = "$2" ]
}

# 主函数
main() {
    log_info "=== Git子模块路径自动调整脚本 ==="
    echo

    # 检查Git版本
    check_git_version

    # 检查环境 - 修复git仓库检测
    check_environment

    # 备份配置文件
    backup_config

    # 读取配置信息
    read_configs

    # 检测变化
    detect_changes

    # 如果没有变化，退出
    if [ -z "$DELETED_PATHS" ] && [ -z "$ADDED_PATHS" ]; then
        log_normal "未检测到子模块路径变化！"
        exit 0
    fi

    # 显示变化
    show_changes

    # 确认操作
    confirm_operation

    # 处理子模块移动
    process_submodules

    # 提交更改
    commit_changes

    # 完成提示
    show_completion
}

# 检查Git版本
check_git_version() {
    log_normal "检查Git版本..."
    GIT_VERSION=$(git --version | awk '{print $3}')

    if version_ge "$GIT_VERSION" "$REQUIRED_GIT_VERSION"; then
        log_info "Git版本 $GIT_VERSION 符合要求（需要 $REQUIRED_GIT_VERSION 或更高版本）"
    else
        log_warn "警告：Git版本 $GIT_VERSION 可能不支持某些功能，建议升级到 $REQUIRED_GIT_VERSION 或更高版本"
    fi
}

# 检查环境 - 修复git仓库检测
check_environment() {
    # 检查是否在git仓库中 - 更可靠的检测方法
    if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        log_error "错误：当前目录不是git仓库！"
        exit 1
    fi

    # 检查.gitmodules文件是否存在
    if [ ! -f "$GIT_MODULES_FILE" ]; then
        log_error "错误：未找到.gitmodules文件！"
        exit 1
    fi

    # 检查工作区是否干净
    if ! git diff --quiet; then
        log_warn "警告：工作区有未提交的更改，请先提交或 stash"
        read -p "是否继续？(y/n) " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            log_normal "操作已取消"
            exit 0
        fi
    fi
}

# 备份配置文件
backup_config() {
    cp "$GIT_MODULES_FILE" "$GIT_MODULES_FILE$BACKUP_SUFFIX"
    log_info "已备份.gitmodules到.gitmodules$BACKUP_SUFFIX"
}

# 读取配置信息
read_configs() {
    log_normal "正在读取当前git配置中的子模块信息..."
    CURRENT_SUBMODULES=$(git config --file .git/config --get-regexp 'submodule\..*\.path' | sort)

    log_normal "正在读取修改后的.gitmodules文件中的子模块信息..."
    NEW_SUBMODULES=$(git config --file "$GIT_MODULES_FILE" --get-regexp 'submodule\..*\.path' | sort)

    # 找出被删除的子模块路径
    DELETED_PATHS=$(comm -23 <(echo "$CURRENT_SUBMODULES" | awk '{print $2}') <(echo "$NEW_SUBMODULES" | awk '{print $2}'))

    # 找出新增的子模块路径
    ADDED_PATHS=$(comm -13 <(echo "$CURRENT_SUBMODULES" | awk '{print $2}') <(echo "$NEW_SUBMODULES" | awk '{print $2}'))

    # 清理空行
    DELETED_PATHS_CLEAN=$(echo "$DELETED_PATHS" | sed '/^$/d')
    ADDED_PATHS_CLEAN=$(echo "$ADDED_PATHS" | sed '/^$/d')
}

# 检测变化
detect_changes() {
    # 构建子模块映射
    build_submodule_maps
}

# 构建子模块映射 - 兼容bash 3.x，使用普通数组模拟关联数组
build_submodule_maps() {
    # 清空数组
    submodule_map=()
    new_submodule_urls=()

    # 从当前配置中获取所有子模块的完整信息
    while IFS= read -r line; do
        if [[ $line =~ ^submodule\.(.*)\.path[[:space:]]+(.*)$ ]]; then
            name="${BASH_REMATCH[1]}"
            path="${BASH_REMATCH[2]}"
            url=$(git config --file .git/config --get "submodule.$name.url" 2>/dev/null || true)
            branch=$(git config --file .git/config --get "submodule.$name.branch" 2>/dev/null || true)

            if [ -n "$url" ]; then
                # 使用"key=value"格式存储，兼容bash 3.x
                submodule_map+=("$path=$name|$url|$branch")
            fi
        fi
    done <<< "$CURRENT_SUBMODULES"

    # 从新配置中获取所有子模块的URL和分支
    while IFS= read -r line; do
        if [[ $line =~ ^submodule\.(.*)\.path[[:space:]]+(.*)$ ]]; then
            name="${BASH_REMATCH[1]}"
            path="${BASH_REMATCH[2]}"
            url=$(git config --file "$GIT_MODULES_FILE" --get "submodule.$name.url" 2>/dev/null || true)
            branch=$(git config --file "$GIT_MODULES_FILE" --get "submodule.$name.branch" 2>/dev/null || true)

            if [ -n "$url" ]; then
                # 使用URL+branch作为键，支持分支匹配
                local key="$url|$branch"
                new_submodule_urls+=("$key=$path|$name")
            fi
        fi
    done <<< "$NEW_SUBMODULES"
}

# 获取子模块信息 - 兼容bash 3.x的get函数
get_submodule_info() {
    local key="$1"
    local array_name="$2"

    # 使用eval获取数组内容
    local array
    eval "array=(\"\${$array_name[@]}\")"

    # 遍历数组查找匹配的键
    for item in "${array[@]}"; do
        if [[ $item =~ ^$key= ]]; then
            echo "${item#$key=}"
            return 0
        fi
    done

    return 1
}

# 查找新子模块URL - 兼容bash 3.x的查找函数
find_new_submodule_url() {
    local url="$1"

    # 遍历new_submodule_urls数组查找匹配的URL
    for item in "${new_submodule_urls[@]}"; do
        if [[ $item =~ ^$url\| ]]; then
            echo "${item%%=*}"
            return 0
        fi
    done

    return 1
}

# 显示变化
show_changes() {
    echo
    log_warn "检测到以下子模块路径变化："
    echo "------------------------"

    if [ ! -z "$DELETED_PATHS" ]; then
        log_normal "被删除的路径："
        echo "$DELETED_PATHS" | while read path; do
            if [ -n "$path" ]; then
                echo -e "  - ${RED}$path${NC}"
            fi
        done
    fi

    if [ ! -z "$ADDED_PATHS" ]; then
        log_normal "新增的路径："
        echo "$ADDED_PATHS" | while read path; do
            if [ -n "$path" ]; then
                echo -e "  + ${GREEN}$path${NC}"
            fi
        done
    fi

    echo "------------------------"
}

# 确认操作
confirm_operation() {
    read -p "是否继续执行移动操作？(y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        log_normal "操作已取消"
        exit 0
    fi
}

# 处理子模块移动
process_submodules() {
    echo
    log_info "开始处理子模块移动..."
    echo

    # 处理每个被删除的路径
    echo "$DELETED_PATHS_CLEAN" | while read OLD_PATH; do
        if [ -z "$OLD_PATH" ]; then
            continue
        fi

        process_single_submodule "$OLD_PATH"
    done

    # 处理剩余的新路径
    process_remaining_paths
}

# 处理单个子模块
process_single_submodule() {
    local OLD_PATH="$1"
    log_normal "处理旧路径：$OLD_PATH"

    # 获取旧路径的子模块信息
    local info=$(get_submodule_info "$OLD_PATH" "submodule_map")
    if [ -n "$info" ]; then
        IFS='|' read -r OLD_NAME OLD_URL OLD_BRANCH <<< "$info"
        log_normal "  找到子模块：$OLD_NAME (URL: $OLD_URL, 分支: ${OLD_BRANCH:-未指定})"

        # 查找对应的新路径（支持分支匹配）
        local found=false
        local matching_key=$(find_new_submodule_url "$OLD_URL")

        if [ -n "$matching_key" ]; then
            local new_info=$(get_submodule_info "$matching_key" "new_submodule_urls")
            if [ -n "$new_info" ]; then
                IFS='|' read -r NEW_PATH NEW_NAME <<< "$new_info"
                IFS='|' read -r NEW_URL NEW_BRANCH <<< "$matching_key"

                log_normal "  对应新路径：$NEW_PATH (名称: $NEW_NAME, 分支: ${NEW_BRANCH:-$DEFAULT_BRANCH})"

                # 使用新配置中的分支信息，如果没有则使用旧的或默认
                local target_branch=$(git config --file "$GIT_MODULES_FILE" --get "submodule.$NEW_NAME.branch" 2>/dev/null || echo "$OLD_BRANCH" || echo "$DEFAULT_BRANCH")
                log_normal "  使用分支：$target_branch"

                # 执行移动操作
                move_submodule "$OLD_PATH" "$NEW_PATH" "$OLD_URL" "$target_branch" "$OLD_NAME"

                # 从映射中移除已处理的条目（bash 3.x不支持unset特定索引，这里跳过）
                found=true
            fi
        fi

        if [ "$found" = false ]; then
            log_warn "  警告：未找到对应的新路径，请手动处理"
        fi
    else
        log_error "  错误：无法获取子模块信息，请手动处理"
    fi
}

# 移动子模块
move_submodule() {
    local OLD_PATH="$1"
    local NEW_PATH="$2"
    local URL="$3"
    local BRANCH="$4"
    local NAME="$5"

    log_normal "  1. 取消注册并删除旧路径..."
    git submodule deinit -f "$OLD_PATH" 2>/dev/null || true
    git rm -f "$OLD_PATH" 2>/dev/null || true

    # 清理.git/modules目录
    if [ -n "$NAME" ] && [ -d ".git/modules/$NAME" ]; then
        rm -rf ".git/modules/$NAME"
    fi

    log_normal "  2. 创建新路径的父目录..."
    mkdir -p "$(dirname "$NEW_PATH")"

    log_normal "  3. 重新添加子模块到新路径..."
    git submodule add --branch "$BRANCH" "$URL" "$NEW_PATH"

    log_info "  ✓ 子模块移动完成：$OLD_PATH -> $NEW_PATH"
    echo
}

# 处理剩余的新路径
process_remaining_paths() {
    for item in "${new_submodule_urls[@]}"; do
        # 提取键和值
        local key="${item%%=*}"
        local value="${item#$key=}"

        IFS='|' read -r NEW_PATH NEW_NAME <<< "$value"
        IFS='|' read -r URL BRANCH <<< "$key"

        # 检查是否已经处理过
        local processed=false
        for old_path in $DELETED_PATHS_CLEAN; do
            local old_info=$(get_submodule_info "$old_path" "submodule_map")
            if [ -n "$old_info" ]; then
                local old_url=$(echo "$old_info" | cut -d'|' -f2)
                if [ "$old_url" = "$URL" ]; then
                    processed=true
                    break
                fi
            fi
        done

        if [ "$processed" = false ]; then
            log_warn "注意：发现新增的子模块 $NEW_PATH (分支: ${BRANCH:-$DEFAULT_BRANCH})，可能需要手动处理"
        fi
    done
}

# 提交更改
commit_changes() {
    log_normal "同步.gitmodules文件..."
    git add "$GIT_MODULES_FILE"

    log_normal "准备提交更改..."
    git commit -m "Auto-move submodules: update paths based on .gitmodules"
}

# 显示完成信息
show_completion() {
    echo
    log_info "=== 操作完成！ ==="
    log_normal "子模块路径已成功更新。"
    log_normal "请运行 git submodule update --init --recursive 确保所有子模块都正确初始化。"
    echo
    log_normal "如果遇到问题，请检查："
    log_normal "1. .gitmodules$BACKUP_SUFFIX 备份文件"
    log_normal "2. 确保所有子模块的URL和分支配置正确"
    log_normal "3. 可以使用 git status 查看当前状态"
}

# 运行主函数
main "$@"