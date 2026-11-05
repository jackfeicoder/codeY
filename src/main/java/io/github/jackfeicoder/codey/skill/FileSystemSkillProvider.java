package io.github.jackfeicoder.codey.skill;

import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import java.nio.file.*;
import java.util.*;

/** 只扫描根目录直接子目录；目录异常独立报告，不递归发现项目中的任意 Markdown。 */
public final class FileSystemSkillProvider {
    private final Path userDirectory;
    private final SkillParser parser=new SkillParser();
    public record Discovery(List<SkillDefinition> skills,List<String> warnings) { }
    public FileSystemSkillProvider(Path userDirectory) { this.userDirectory=userDirectory; }
    public Discovery discover(WorkspaceBinding workspace) {
        var skills=new ArrayList<SkillDefinition>(); var warnings=new ArrayList<String>();
        scan(userDirectory,"user",skills,warnings);
        if(workspace.mode()==WorkspaceBinding.Mode.DIRECTORY) {
            try {
                Path base=Path.of(workspace.path()).toRealPath(),root=base.resolve(".agents/skills");
                if(Files.exists(root,LinkOption.NOFOLLOW_LINKS) && !root.toRealPath().startsWith(base))warnings.add("项目技能目录指向工作区外，已跳过。");
                else scan(root,"project",skills,warnings);
            } catch(Exception failed){warnings.add("项目工作区不可用，未扫描项目技能。");}
        }
        return new Discovery(List.copyOf(skills),List.copyOf(warnings));
    }
    private void scan(Path root,String source,List<SkillDefinition> skills,List<String> warnings) {
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        try {
            // 技能根和条目均不跟随符号链接，避免发现范围被静默扩展。
            if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))throw new IllegalArgumentException();
            Path realRoot=root.toRealPath();
            var candidates=new ArrayList<Path>(); boolean overflow=false;int examined=0;
            try(var paths=Files.newDirectoryStream(root)) {
                for(Path path:paths) {
                    if(candidates.size()==128 || ++examined>512){overflow=true;break;}
                    if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))candidates.add(path);
                }
            }
            if(overflow)warnings.add(source+" 技能目录超过128项，当前只发现部分条目。");
            candidates.sort(Comparator.comparing(p->p.getFileName().toString()));
            for(Path directory:candidates) {
                Path file=directory.resolve("SKILL.md"); if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))continue;
                try {
                    if(!file.toRealPath().startsWith(realRoot))throw new IllegalArgumentException();
                    var parsed=parser.parse(file,source); skills.add(parsed.definition());warnings.addAll(parsed.warnings());
                } catch(Exception invalid) {warnings.add(source+" 技能条目无效，已跳过："+directory.getFileName().toString().replaceAll("[\\p{Cntrl}]"," "));}
            }
        } catch(Exception failed) {warnings.add(source+" 技能目录读取失败；其他来源继续可用。");}
    }
}
